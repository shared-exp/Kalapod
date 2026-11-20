package ir.kalapod.app.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.WindowInsets
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
import ir.kalapod.app.ui.documents.DocumentDetailScreen
import ir.kalapod.app.ui.documents.DocumentsScreen
import ir.kalapod.app.ui.products.ProductEditScreen
import ir.kalapod.app.ui.purchase.PurchaseScreen
import ir.kalapod.app.ui.sale.SaleScreen
import ir.kalapod.app.ui.products.ProductListScreen
import androidx.navigation.NavType
import androidx.navigation.navArgument

enum class TopLevel(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "خانه", Icons.Outlined.Home),
    PURCHASE("purchase", "خرید", Icons.Outlined.AddShoppingCart),
    SALE("sale", "فروش", Icons.Outlined.PointOfSale),
    INVENTORY("inventory", "انبار", Icons.Outlined.Inventory2),
    REPORTS("reports", "فاکتورها", Icons.Outlined.BarChart),
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
        // Each screen handles its own status-bar inset; avoid double top padding.
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            // Only on top-level tabs; detail/edit screens get the full height.
            if (TopLevel.entries.none { it.route == current?.route }) return@Scaffold
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
            composable(TopLevel.PURCHASE.route) { PurchaseScreen(
                    onCreateProduct = { code -> nav.navigate(if (code == null) "product/edit" else "product/edit?barcode=$code") },
                    onDefineNamed = { name -> nav.navigate("product/edit?name=" + android.net.Uri.encode(name)) },
                ) }
            composable(TopLevel.SALE.route) { SaleScreen(
                    onCreateProduct = { code -> nav.navigate(if (code == null) "product/edit" else "product/edit?barcode=$code") },
                    onDefineNamed = { name -> nav.navigate("product/edit?name=" + android.net.Uri.encode(name)) },
                ) }
            composable(TopLevel.INVENTORY.route) {
                ProductListScreen(
                    onAdd = { nav.navigate("product/edit") },
                    onOpen = { id -> nav.navigate("product/edit?id=$id") },
                )
            }
            composable(
                "product/edit?id={id}&barcode={barcode}&name={name}",
                arguments = listOf(
                    navArgument("id") { type = NavType.LongType; defaultValue = 0L },
                    navArgument("barcode") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("name") { type = NavType.StringType; nullable = true; defaultValue = null },
                ),
            ) { ProductEditScreen(onDone = { nav.popBackStack() }, onOpenDocument = { nav.navigate("document/$it") }) }
            // Reports (step 3.3) will live here; until then the tab lists documents.
            composable(TopLevel.REPORTS.route) { DocumentsScreen(onOpen = { nav.navigate("document/$it") }) }
            composable("document/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                DocumentDetailScreen(onBack = { nav.popBackStack() }, onOpen = { nav.navigate("document/$it") })
            }
        }
    }
}
