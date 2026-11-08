/* Second-screen HUD placement: stacked vs side by side, bounds and crop. */
#include "hud_layout.h"
#include "harness.h"

static void fits(int w, int h, int rw, int rh) {
    hud::Rect hud, fav, src;
    hud::layout(w, h, rw, rh, &hud, &fav, &src);

    CHECK(hud.left >= 0 && hud.right <= w && hud.top >= 0 && hud.bottom <= h);
    CHECK(fav.left >= 0 && fav.right <= w && fav.top >= 0 && fav.bottom <= h);
    CHECK(hud.right > hud.left && hud.bottom > hud.top);
    CHECK(fav.right > fav.left && fav.bottom > fav.top);

    int stacked = hud.bottom <= fav.top;
    int side_by_side = fav.right <= hud.left;
    CHECK(stacked || side_by_side);

    CHECK_INT(src.left, 0);
    CHECK_INT(src.top, 0);
    CHECK_INT(src.right, (long)(rw * hud::kFavCrop));
    CHECK_INT(src.bottom, rh);
}

TEST(stacked_layout_puts_the_list_below_the_hud) {
    hud::Rect hud, fav, src;
    hud::layout(1280, 720, 1280, 720, &hud, &fav, &src);

    CHECK_INT(hud.left, 160);
    CHECK_INT(hud.top, 0);
    CHECK_INT(hud.right, 1120);
    CHECK_INT(hud.bottom, 540);

    CHECK_INT(fav.left, 572);
    CHECK_INT(fav.top, 540);
    CHECK_INT(fav.right, 707);
    CHECK_INT(fav.bottom, 720);

    CHECK_INT(src.right, 537);
    CHECK_INT(src.bottom, 720);
}

TEST(ultra_wide_window_puts_the_list_beside_the_hud) {
    hud::Rect hud, fav, src;
    hud::layout(2400, 1080, 1280, 720, &hud, &fav, &src);

    CHECK_INT(fav.left, 0);
    CHECK_INT(hud.left, fav.right);
    CHECK_NEAR(hud.right, 2400, 2);
    CHECK_NEAR(hud.top, fav.top, 1);
    CHECK_NEAR(hud.bottom, fav.bottom, 1);
}

TEST(favourites_crop_is_42_percent_of_the_menu) {
    hud::Rect hud, fav, src;
    hud::layout(1920, 1080, 1920, 1080, &hud, &fav, &src);

    CHECK_INT(src.right, 806);  /* 1920 * 0.42 */
    CHECK_INT(src.bottom, 1080);
}

TEST(layout_stays_inside_the_window) {
    fits(1280, 720, 1280, 720);
    fits(2400, 1080, 1280, 720);
    fits(1920, 1080, 1280, 720);
    fits(2560, 1440, 1920, 1080);
    fits(800, 600, 800, 600);
    fits(3440, 1440, 2560, 1440);
}

int main(void) {
    RUN(stacked_layout_puts_the_list_below_the_hud);
    RUN(ultra_wide_window_puts_the_list_beside_the_hud);
    RUN(favourites_crop_is_42_percent_of_the_menu);
    RUN(layout_stays_inside_the_window);
    return th_report("hud_layout");
}
