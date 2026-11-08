// Second-screen layout for the dual build, kept free of D3D so tests/native
// can compile it on the host. hud_screen.cpp wraps it with RECT and its
// globals.
#pragma once

namespace hud {

struct Rect {
    long left, top, right, bottom;
};

// Share of the menu's width that holds the favourites list.
inline constexpr float kFavCrop = 0.42f;

// The HUD image and the favourites list (the left part of the favourites
// menu) side by side or one above the other, whichever gives the HUD more
// room.
inline void layout(int win_w, int win_h, int rt_w, int rt_h,
                   Rect *hud, Rect *fav, Rect *fav_src) {
    float W = (float)win_w, H = (float)win_h, a = (float)rt_w / rt_h, fa = a * kFavCrop;
    // stacked: HUD on top at full width, list below; side by side: list left
    float hs_w = W, hs_h = W / a;
    if (hs_h > H * 0.75f) { hs_h = H * 0.75f; hs_w = hs_h * a; }
    float rem_h = H - hs_h;
    float s_w = W / (a + fa), s_h = s_w;  // side by side: common height h, widths a*h and fa*h
    if (s_h > H) s_h = H;
    float side_hud_w = s_h * a;
    if (side_hud_w > hs_w) {
        float x0 = (W - s_h * (a + fa)) / 2, y0 = (H - s_h) / 2;
        *fav = Rect{(long)x0, (long)y0, (long)(x0 + s_h * fa), (long)(y0 + s_h)};
        *hud = Rect{fav->right, (long)y0, (long)(fav->right + side_hud_w), (long)(y0 + s_h)};
    } else {
        *hud = Rect{(long)((W - hs_w) / 2), 0, (long)((W + hs_w) / 2), (long)hs_h};
        float fh = rem_h, fw = fh * fa;
        if (fw > W) { fw = W; fh = fw / fa; }
        *fav = Rect{(long)((W - fw) / 2), (long)hs_h, (long)((W + fw) / 2), (long)(hs_h + fh)};
    }
    *fav_src = Rect{0, 0, (long)(rt_w * kFavCrop), (long)rt_h};
}

} // namespace hud
