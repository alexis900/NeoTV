package com.gathin.tvhome;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * The Continue watching row (config.xml continue_watching): a caption and one row of cards between the
 * clock and the dock's title. It owns its own focus and keys, like the library button: the glass and the
 * hero step aside while a card is focused (Host.onRowFocused), Up leaves for the top bar, Down returns to
 * the dock, and Left/Right stop at the row's ends instead of escaping into the grid.
 */
final class ContinueRow extends LinearLayout {

    interface Host {
        void onRowFocused();

        void onRowUp();

        void onRowDown();

        void onRowOpen(ContinueWatching.Item item);

        /** True for a view in the top bar, whose Down is allowed to land here. */
        boolean inTopBar(View view);
    }

    static final int CARD_W_DP = 160, CARD_H_DP = 90;
    private static final float FOCUS_SCALE = 1.08f;

    private final Host host;
    private final float density;
    private final LinearLayout cards;
    private final HorizontalScrollView scroller;
    private final List<View> cardViews = new ArrayList<>();
    private final java.util.Map<ContinueWatching.Item, ImageView> artViews = new HashMap<>();
    private int lastIndex;
    private boolean wanted;    // the owner of the row wants it on screen (focus is in the top bar, row or dock)
    /** Set by focusCard (Up from the dock) until that focus has arrived. */
    private boolean focusRequested;
    private boolean fits = true;   // false: the screen has no room between the clock and the dock's title

    ContinueRow(Context context, Host host, String caption) {
        super(context);
        this.host = host;
        this.density = context.getResources().getDisplayMetrics().density;
        setOrientation(VERTICAL);
        setVisibility(GONE);
        setClipChildren(false);
        setClipToPadding(false);

        TextView title = new TextView(context);
        title.setText(caption);
        title.setAllCaps(true);
        title.setLetterSpacing(0.25f);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        title.setTextColor(0xB3FFFFFF);
        title.setTypeface(UiFont.get(context, R.string.font_medium));
        LayoutParams tlp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.setMarginStart(dp(12));
        addView(title, tlp);

        scroller = new HorizontalScrollView(context);
        scroller.setHorizontalScrollBarEnabled(false);
        scroller.setOverScrollMode(OVER_SCROLL_NEVER);
        scroller.setClipChildren(false);
        scroller.setClipToPadding(false);
        scroller.setSmoothScrollingEnabled(true);
        cards = new LinearLayout(context);
        cards.setOrientation(HORIZONTAL);
        cards.setClipChildren(false);
        cards.setClipToPadding(false);
        // Room for the focused card's zoom on every side, so the scroller does not clip it.
        int pad = dp(12);
        cards.setPadding(pad, pad, pad, pad);
        scroller.addView(cards, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(scroller, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    // Focus only comes here on purpose: Up from the dock, Down from the top bar, or along the row. When the grid
    // rebuilds its tiles the focused one is detached and Android hands focus to the first focusable view, which
    // is a card: that stray focus goes straight back to the dock.
    private final android.view.ViewTreeObserver.OnGlobalFocusChangeListener strayFocus = this::onGlobalFocus;

    private void onGlobalFocus(View oldFocus, View newFocus) {
        if (newFocus == null || !isInRow(newFocus)) return;
        boolean wanted = focusRequested || (oldFocus != null && (isInRow(oldFocus) || host.inTopBar(oldFocus)));
        focusRequested = false;
        if (!wanted) post(host::onRowDown);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnGlobalFocusChangeListener(strayFocus);
    }

    @Override
    protected void onDetachedFromWindow() {
        getViewTreeObserver().removeOnGlobalFocusChangeListener(strayFocus);
        super.onDetachedFromWindow();
    }

    private boolean isInRow(View v) {
        for (android.view.ViewParent p = v.getParent(); p != null; p = p.getParent()) {
            if (p == this) return true;
        }
        return false;
    }

    boolean hasItems() {
        return !cardViews.isEmpty();
    }

    boolean isShowing() {
        return getVisibility() == VISIBLE && hasItems();
    }

    /** The first card's id, for the top bar's Down; View.NO_ID while the row is not on screen. */
    int firstCardId() {
        return isShowing() ? cardViews.get(0).getId() : View.NO_ID;
    }

    void setItems(List<ContinueWatching.Item> items) {
        boolean hadFocus = hasFocus();
        cards.removeAllViews();
        cardViews.clear();
        artViews.clear();
        for (int i = 0; i < items.size(); i++) {
            View card = card(items.get(i), i);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(CARD_W_DP), dp(CARD_H_DP));
            if (i > 0) lp.setMarginStart(dp(18));
            cards.addView(card, lp);
            cardViews.add(card);
        }
        lastIndex = Math.min(lastIndex, Math.max(0, cardViews.size() - 1));
        applyVisibility();
        if (hadFocus) {
            if (hasItems()) focusCard();
            else host.onRowDown();
        }
    }

    /** Shown only while focus is up here: the grid scrolls through this space once Down leaves the dock. */
    void setWanted(boolean wanted) {
        if (this.wanted == wanted) return;
        this.wanted = wanted;
        applyVisibility();
    }

    void setFits(boolean fits) {
        if (this.fits == fits) return;
        this.fits = fits;
        applyVisibility();
    }

    private void applyVisibility() {
        int v = wanted && fits && hasItems() ? VISIBLE : GONE;
        if (getVisibility() == v) return;
        animate().cancel();
        if (v == VISIBLE) {
            setAlpha(0f);
            setVisibility(VISIBLE);
            animate().alpha(1f).setDuration(200).start();
        } else {
            setVisibility(GONE);
        }
    }

    /** A poster that arrived after the row was drawn takes the banner's place. */
    void showPoster(ContinueWatching.Item item) {
        ImageView art = artViews.get(item);
        if (art == null || item.poster == null) return;
        art.setImageBitmap(item.poster);
        art.setAlpha(0f);
        art.animate().alpha(1f).setDuration(250).start();
    }

    void focusCard() {
        if (!hasItems()) return;
        focusRequested = true;
        if (!cardViews.get(Math.min(lastIndex, cardViews.size() - 1)).requestFocus()) focusRequested = false;
    }

    private View card(ContinueWatching.Item item, int index) {
        Context ctx = getContext();
        FrameLayout card = new FrameLayout(ctx);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        card.setContentDescription(item.title);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1E2433);
        bg.setCornerRadius(dp(12));
        card.setBackground(bg);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(12));
            }
        });
        card.setClipToOutline(true);

        ImageView art = new ImageView(ctx);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (item.poster != null) {
            art.setImageBitmap(item.poster);
        } else {
            // No local poster: the app's own banner, dimmed, under the title. A resource of the app itself,
            // so still no network.
            Drawable banner = banner(item.pkg);
            if (banner != null) {
                art.setImageDrawable(banner);
                art.setAlpha(0.35f);
            }
        }
        artViews.put(item, art);
        card.addView(art, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        View scrim = new View(ctx);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xE6000000, 0x00000000}));
        card.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(60), Gravity.BOTTOM));

        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(VERTICAL);
        text.setPadding(dp(10), 0, dp(10), dp(item.progress >= 0 ? 10 : 8));
        TextView name = label(item.title, 14, 0xFFFFFFFF, R.string.font_medium);
        text.addView(name);
        if (!item.subtitle.isEmpty()) text.addView(label(item.subtitle, 11, 0xB3FFFFFF, R.string.font_regular));
        card.addView(text, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        if (item.progress >= 0) {
            View track = new View(ctx);
            track.setBackgroundColor(0x40FFFFFF);
            card.addView(track, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.BOTTOM));
            View bar = new View(ctx);
            bar.setBackgroundColor(Color.WHITE);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(0, dp(3), Gravity.BOTTOM | Gravity.START);
            card.addView(bar, blp);
            card.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                int w = Math.round((r - l) * item.progress);
                if (blp.width != w) {
                    blp.width = w;
                    bar.post(bar::requestLayout);
                }
            });
        }

        // The ring is drawn over the card, so it shows on light posters too.
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(12));
        ring.setStroke(dp(2), Color.WHITE);
        ring.setColor(Color.TRANSPARENT);
        card.setOnFocusChangeListener((v, focused) -> {
            v.animate().cancel();
            v.animate().scaleX(focused ? FOCUS_SCALE : 1f).scaleY(focused ? FOCUS_SCALE : 1f).setDuration(150).start();
            v.setElevation(focused ? dp(8) : 0);
            card.setForeground(focused ? ring : null);
            // A stray focus also lands here; strayFocus has already queued the move back to the dock, which puts
            // the glass and the title back.
            if (focused) {
                lastIndex = index;
                host.onRowFocused();
            }
        });
        card.setOnClickListener(v -> host.onRowOpen(item));
        card.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN;
            }
            switch (keyCode) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    v.playSoundEffect(SoundEffectConstants.NAVIGATION_UP);
                    host.onRowUp();
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    v.playSoundEffect(SoundEffectConstants.NAVIGATION_DOWN);
                    host.onRowDown();
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    return index == 0;                        // the row's start: stay put
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return index == cardViews.size() - 1;     // the row's end: stay put
                default:
                    return false;
            }
        });
        return card;
    }

    private TextView label(String s, int sp, int color, int font) {
        TextView t = new TextView(getContext());
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(UiFont.get(getContext(), font));
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setIncludeFontPadding(false);
        t.setPadding(0, dp(2), 0, 0);
        return t;
    }

    private Drawable banner(String pkg) {
        PackageManager pm = getContext().getPackageManager();
        try {
            Drawable d = pm.getApplicationBanner(pkg);
            return d != null ? d : pm.getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    private int dp(int v) {
        return Math.round(v * density);
    }
}
