package com.macrobase.app.core.designsystem

import androidx.annotation.DrawableRes
import com.macrobase.app.R

/**
 * Phosphor Icons 2.1.1 (MIT, assets/licenses/Phosphor-MIT.txt), bundled as vector drawables
 * res/drawable/ic_ph_<name>_<weight>.xml. Most icons use [light]; the active tab and icons on filled
 * buttons use [regular] (designs/design.txt section 8).
 */
enum class Ph(@DrawableRes val light: Int, @DrawableRes val regular: Int) {
    ArrowCounterClockwise(R.drawable.ic_ph_arrow_counter_clockwise_light, R.drawable.ic_ph_arrow_counter_clockwise_regular),
    ArrowLeft(R.drawable.ic_ph_arrow_left_light, R.drawable.ic_ph_arrow_left_regular),
    ArrowRight(R.drawable.ic_ph_arrow_right_light, R.drawable.ic_ph_arrow_right_regular),
    ArrowUpRight(R.drawable.ic_ph_arrow_up_right_light, R.drawable.ic_ph_arrow_up_right_regular),
    ArrowsLeftRight(R.drawable.ic_ph_arrows_left_right_light, R.drawable.ic_ph_arrows_left_right_regular),
    Barcode(R.drawable.ic_ph_barcode_light, R.drawable.ic_ph_barcode_regular),
    Basket(R.drawable.ic_ph_basket_light, R.drawable.ic_ph_basket_regular),
    BowlFood(R.drawable.ic_ph_bowl_food_light, R.drawable.ic_ph_bowl_food_regular),
    CalendarBlank(R.drawable.ic_ph_calendar_blank_light, R.drawable.ic_ph_calendar_blank_regular),
    CalendarCheck(R.drawable.ic_ph_calendar_check_light, R.drawable.ic_ph_calendar_check_regular),
    Camera(R.drawable.ic_ph_camera_light, R.drawable.ic_ph_camera_regular),
    CaretDown(R.drawable.ic_ph_caret_down_light, R.drawable.ic_ph_caret_down_regular),
    CaretLeft(R.drawable.ic_ph_caret_left_light, R.drawable.ic_ph_caret_left_regular),
    CaretRight(R.drawable.ic_ph_caret_right_light, R.drawable.ic_ph_caret_right_regular),
    CaretUp(R.drawable.ic_ph_caret_up_light, R.drawable.ic_ph_caret_up_regular),
    ChartBar(R.drawable.ic_ph_chart_bar_light, R.drawable.ic_ph_chart_bar_regular),
    ChartLine(R.drawable.ic_ph_chart_line_light, R.drawable.ic_ph_chart_line_regular),
    ChartPieSlice(R.drawable.ic_ph_chart_pie_slice_light, R.drawable.ic_ph_chart_pie_slice_regular),
    Check(R.drawable.ic_ph_check_light, R.drawable.ic_ph_check_regular),
    CheckCircle(R.drawable.ic_ph_check_circle_light, R.drawable.ic_ph_check_circle_regular),
    Clock(R.drawable.ic_ph_clock_light, R.drawable.ic_ph_clock_regular),
    Coffee(R.drawable.ic_ph_coffee_light, R.drawable.ic_ph_coffee_regular),
    Cookie(R.drawable.ic_ph_cookie_light, R.drawable.ic_ph_cookie_regular),
    CookingPot(R.drawable.ic_ph_cooking_pot_light, R.drawable.ic_ph_cooking_pot_regular),
    Copy(R.drawable.ic_ph_copy_light, R.drawable.ic_ph_copy_regular),
    Crown(R.drawable.ic_ph_crown_light, R.drawable.ic_ph_crown_regular),
    Database(R.drawable.ic_ph_database_light, R.drawable.ic_ph_database_regular),
    DotsThree(R.drawable.ic_ph_dots_three_light, R.drawable.ic_ph_dots_three_regular),
    DownloadSimple(R.drawable.ic_ph_download_simple_light, R.drawable.ic_ph_download_simple_regular),
    Drop(R.drawable.ic_ph_drop_light, R.drawable.ic_ph_drop_regular),
    Export(R.drawable.ic_ph_export_light, R.drawable.ic_ph_export_regular),
    Flame(R.drawable.ic_ph_flame_light, R.drawable.ic_ph_flame_regular),
    Flashlight(R.drawable.ic_ph_flashlight_light, R.drawable.ic_ph_flashlight_regular),
    ForkKnife(R.drawable.ic_ph_fork_knife_light, R.drawable.ic_ph_fork_knife_regular),
    GearSix(R.drawable.ic_ph_gear_six_light, R.drawable.ic_ph_gear_six_regular),
    House(R.drawable.ic_ph_house_light, R.drawable.ic_ph_house_regular),
    Image(R.drawable.ic_ph_image_light, R.drawable.ic_ph_image_regular),
    Images(R.drawable.ic_ph_images_light, R.drawable.ic_ph_images_regular),
    Info(R.drawable.ic_ph_info_light, R.drawable.ic_ph_info_regular),
    Lightning(R.drawable.ic_ph_lightning_light, R.drawable.ic_ph_lightning_regular),
    List(R.drawable.ic_ph_list_light, R.drawable.ic_ph_list_regular),
    MagnifyingGlass(R.drawable.ic_ph_magnifying_glass_light, R.drawable.ic_ph_magnifying_glass_regular),
    Medal(R.drawable.ic_ph_medal_light, R.drawable.ic_ph_medal_regular),
    Minus(R.drawable.ic_ph_minus_light, R.drawable.ic_ph_minus_regular),
    Moon(R.drawable.ic_ph_moon_light, R.drawable.ic_ph_moon_regular),
    NotePencil(R.drawable.ic_ph_note_pencil_light, R.drawable.ic_ph_note_pencil_regular),
    PencilSimple(R.drawable.ic_ph_pencil_simple_light, R.drawable.ic_ph_pencil_simple_regular),
    Plus(R.drawable.ic_ph_plus_light, R.drawable.ic_ph_plus_regular),
    PlusCircle(R.drawable.ic_ph_plus_circle_light, R.drawable.ic_ph_plus_circle_regular),
    Scales(R.drawable.ic_ph_scales_light, R.drawable.ic_ph_scales_regular),
    Scan(R.drawable.ic_ph_scan_light, R.drawable.ic_ph_scan_regular),
    SquaresFour(R.drawable.ic_ph_squares_four_light, R.drawable.ic_ph_squares_four_regular),
    Target(R.drawable.ic_ph_target_light, R.drawable.ic_ph_target_regular),
    Trash(R.drawable.ic_ph_trash_light, R.drawable.ic_ph_trash_regular),
    TrendDown(R.drawable.ic_ph_trend_down_light, R.drawable.ic_ph_trend_down_regular),
    TrendUp(R.drawable.ic_ph_trend_up_light, R.drawable.ic_ph_trend_up_regular),
    Trophy(R.drawable.ic_ph_trophy_light, R.drawable.ic_ph_trophy_regular),
    User(R.drawable.ic_ph_user_light, R.drawable.ic_ph_user_regular),
    UserCircle(R.drawable.ic_ph_user_circle_light, R.drawable.ic_ph_user_circle_regular),
    UploadSimple(R.drawable.ic_ph_upload_simple_light, R.drawable.ic_ph_upload_simple_regular),
    Warning(R.drawable.ic_ph_warning_light, R.drawable.ic_ph_warning_regular),
    WarningCircle(R.drawable.ic_ph_warning_circle_light, R.drawable.ic_ph_warning_circle_regular),
    X(R.drawable.ic_ph_x_light, R.drawable.ic_ph_x_regular),
    XCircle(R.drawable.ic_ph_x_circle_light, R.drawable.ic_ph_x_circle_regular);

    companion object {
        /** The filled check-circle, for "Your current rank". */
        @DrawableRes
        val CheckCircleFill: Int = R.drawable.ic_ph_check_circle_fill
    }
}
