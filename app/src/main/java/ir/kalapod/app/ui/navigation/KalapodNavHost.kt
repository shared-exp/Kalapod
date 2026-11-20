package ir.kalapod.app.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddShoppingCart
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.PointOfSale
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import ir.kalapod.app.ui.dashboard.DashboardScreen
import ir.kalapod.app.ui.common.UnderConstruction

enum class TopLevel(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "خانه", Icons.Outlined.Home),
    PURCHASE("purchase", "خرید", Icons.Outlined.AddShoppingCart),
    SALE("sale", "فروش", Icons.Outlined.PointOfSale),
    INVENTORY("inventory", "انبار", Icons.Outlined.Inventory2),
    REPORTS("reports", "گزارش‌ها", Icons.Outlined.BarChart),
}

@Composable
fun KalapodNavHost() {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination

    fun go(route: String) = nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                TopLevel.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = current?.hierarchy?.any { it.route == tab.route } == true,
                        onClick = { go(tab.route) },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(tab.label, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = TopLevel.HOME.route,
            modifier = Modifier.padding(padding),
            enterTransition = { fadeIn() },
            exitTransition = { fadeOut() },
        ) {
            composable(TopLevel.HOME.route) {
                DashboardScreen(
                    onNewSale = { go(TopLevel.SALE.route) },
                    onNewPurchase = { go(TopLevel.PURCHASE.route) },
                    onOpenInventory = { go(TopLevel.INVENTORY.route) },
                )
            }
            // Screens below are implemented in roadmap steps 1.6–1.8 and 3.3.
            composable(TopLevel.PURCHASE.route) { UnderConstruction("ثبت خرید", "گام ۱.۷") }
            composable(TopLevel.SALE.route) { UnderConstruction("ثبت فروش", "گام ۱.۸") }
            composable(TopLevel.INVENTORY.route) { UnderConstruction("انبار و کالاها", "گام ۱.۶") }
            composable(TopLevel.REPORTS.route) { UnderConstruction("گزارش‌ها و تنظیمات", "گام ۳.۳") }
        }
    }
}
