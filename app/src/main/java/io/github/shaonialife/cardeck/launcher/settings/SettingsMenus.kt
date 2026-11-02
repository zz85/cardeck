package io.github.shaonialife.cardeck.launcher.settings

import androidx.fragment.app.Fragment
import io.github.shaonialife.cardeck.R

/** 车机设置菜单注册表：车机双栏与手机二级菜单共用（菜单项/标题/Fragment 工厂）。 */
object SettingsMenus {
    const val DESKTOP = 0
    const val SUPERSAMPLE = 1
    const val ORIENTATION = 2
    const val DOCK = 3
    const val DEFAULT_MAP = 4
    const val DISPLAY = 5

    data class Item(
        val id: Int,
        val title: String,
        val desc: String,
        val iconRes: Int,
    )

    val ALL =
        listOf(
            Item(DESKTOP, "Home apps", "Checked apps appear on the car home screen", R.drawable.ic_menu_grid),
            Item(SUPERSAMPLE, "Supersampling", "Sharper rendering for portrait apps; 3 modes", R.drawable.ic_menu_zoom),
            Item(ORIENTATION, "Force landscape", "Portrait apps fill the landscape car screen", R.drawable.ic_screen_rotation),
            Item(DOCK, "Dock settings", "Wallpaper dimming and blur", R.drawable.ic_menu_dock),
            Item(DEFAULT_MAP, "Default map", "Map app used on the dashboard", R.drawable.ic_menu_map),
            Item(DISPLAY, "Display", "Stream resolution scale and target frame rate", R.drawable.ic_menu_display),
        )

    fun titleOf(id: Int): String = ALL.first { it.id == id }.title

    fun createFragment(id: Int): Fragment =
        when (id) {
            DESKTOP -> DesktopPanelFragment()
            SUPERSAMPLE -> SupersamplePanelFragment()
            ORIENTATION -> OrientationPanelFragment()
            DOCK -> DockPanelFragment()
            DISPLAY -> DisplayPanelFragment()
            else -> DefaultMapPanelFragment()
        }
}
