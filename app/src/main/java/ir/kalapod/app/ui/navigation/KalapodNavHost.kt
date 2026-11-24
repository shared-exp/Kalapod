package ir.kalapod.app.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddShoppingCart
import androidx.compose.material.icons.outlined.MoreHoriz
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
import ir.kalapod.app.ui.more.MoreScreen
import ir.kalapod.app.ui.more.MoreEntry
import ir.kalapod.app.ui.backup.BackupScreen
import ir.kalapod.app.ui.forecast.OrderSuggestionsScreen
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Backup
import ir.kalapod.app.ui.reports.ReportsScreen
import ir.kalapod.app.ui.more.SettingsScreen
import ir.kalapod.app.ui.parties.PartiesScreen
import ir.kalapod.app.ui.parties.PartyDetailScreen
import ir.kalapod.app.ui.products.ProductEditScreen
import ir.kalapod.app.ui.purchase.PurchaseScreen
import ir.kalapod.app.ui.sale.SaleScreen
import ir.kalapod.app.ui.products.ProductListScreen
import androidx.navigation.NavType
import androidx.navigation.navArgument

/** Purchase screen («ورود کالا»): opened from Inventory, the dashboard and order suggestions. */
const val PURCHASE_ROUTE = "purchase"
/** Sale screen: opened from the dashboard's big «ثبت فروش» button (not a tab). */
const val SALE_ROUTE = "sale"

enum class TopLevel(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "خانه", Icons.Outlined.Home),
    INVENTORY("inventory", "انبار", Icons.Outlined.Inventory2),
    REPORTS("more", "بیشتر", Icons.Outlined.MoreHoriz),
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
                    onNewSale = { nav.navigate(SALE_ROUTE) { launchSingleTop = true } },
                    onNewReturn = { nav.navigate("documents?forReturn=true") },
                    onNewPurchase = { nav.navigate(PURCHASE_ROUTE) },
                    onOpenInventory = { go(TopLevel.INVENTORY.route) },
                    onOpenSuggestions = { nav.navigate("suggestions") },
                    onRestoreBackup = { nav.navigate("backup?restore=true") },
                    onOpenBackup = { nav.navigate("backup") },
                    onOpenProduct = { id -> nav.navigate("product/edit?id=$id") },
                )
            }
            // Screens below are implemented in roadmap steps 1.6–1.8 and 3.3.
            // Bringing goods in (خرید) lives under Inventory: it is how new stock enters the shop.
            composable(PURCHASE_ROUTE) { PurchaseScreen(
                    onBack = { nav.popBackStack() },
                    onCreateProduct = { code -> nav.navigate("product/edit?from=purchase" + (code?.let { "&barcode=$it" } ?: "")) },
                    onDefineNamed = { name, qty -> nav.navigate("product/edit?from=purchase&qty=$qty&name=" + android.net.Uri.encode(name)) },
                    onOpenDocument = { nav.navigate("document/$it") },
                ) }
            composable(SALE_ROUTE) { SaleScreen(
                    onBack = { nav.popBackStack() },
                    onCreateProduct = { code -> nav.navigate("product/edit?from=sale" + (code?.let { "&barcode=$it" } ?: "")) },
                    onDefineNamed = { name, _ -> nav.navigate("product/edit?from=sale&name=" + android.net.Uri.encode(name)) },
                    onOpenDocument = { nav.navigate("document/$it") },
                ) }
            composable(TopLevel.INVENTORY.route) {
                ProductListScreen(
                    onAdd = { nav.navigate(PURCHASE_ROUTE) },
                    onOpen = { id -> nav.navigate("product/edit?id=$id") },
                )
            }
            composable(
                "product/edit?id={id}&barcode={barcode}&name={name}&from={from}&qty={qty}",
                arguments = listOf(
                    navArgument("id") { type = NavType.LongType; defaultValue = 0L },
                    navArgument("barcode") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("name") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("from") { type = NavType.StringType; nullable = true; defaultValue = null },
                    navArgument("qty") { type = NavType.LongType; defaultValue = 0L },
                ),
            ) {
                ProductEditScreen(
                    onDone = { nav.popBackStack() },
                    onOpenDocument = { nav.navigate("document/$it") },
                    onNewBatch = { nav.popBackStack(); nav.navigate(PURCHASE_ROUTE) { launchSingleTop = true } },
                )
            }
            composable(TopLevel.REPORTS.route) {
                MoreScreen(
                    onReports = { nav.navigate("reports") },
                    onDocuments = { nav.navigate("documents") },
                    onParties = { nav.navigate("parties") },
                    onSettings = { nav.navigate("settings") },
                    extra = listOf(
                        MoreEntry("پیشنهاد خرید", "چه چیزی، چقدر و کی بخرید", Icons.Outlined.Insights) { nav.navigate("suggestions") },
                        MoreEntry("پشتیبان‌گیری", "نسخه پشتیبان رمزدار، بازیابی، پشتیبان خودکار", Icons.Outlined.Backup) { nav.navigate("backup") },
                    ),
                )
            }
            composable(
                "documents?forReturn={forReturn}",
                arguments = listOf(navArgument("forReturn") { type = NavType.BoolType; defaultValue = false }),
            ) { entry ->
                val forReturn = entry.arguments?.getBoolean("forReturn") == true
                DocumentsScreen(onOpen = { nav.navigate("document/$it?startReturn=$forReturn") })
            }
            composable("parties") {
                PartiesScreen(
                    onBack = { nav.popBackStack() },
                    onOpen = { kind, id -> nav.navigate("party/${kind.name}/$id") },
                    onAdd = { kind -> nav.navigate("party/${kind.name}/0") },
                )
            }
            composable(
                "party/{kind}/{id}",
                arguments = listOf(navArgument("kind") { type = NavType.StringType }, navArgument("id") { type = NavType.LongType }),
            ) { PartyDetailScreen(onBack = { nav.popBackStack() }, onOpenDocument = { nav.navigate("document/$it") }) }
            composable("reports") { ReportsScreen(onBack = { nav.popBackStack() }) }
            composable("suggestions") {
                OrderSuggestionsScreen(onBack = { nav.popBackStack() }, onGoToPurchase = { nav.popBackStack(); nav.navigate(PURCHASE_ROUTE) })
            }
            composable(
                "backup?restore={restore}",
                arguments = listOf(navArgument("restore") { type = NavType.BoolType; defaultValue = false }),
            ) { entry -> BackupScreen(onBack = { nav.popBackStack() }, startRestore = entry.arguments?.getBoolean("restore") == true) }
            composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
            composable(
                "document/{id}?startReturn={startReturn}",
                arguments = listOf(
                    navArgument("id") { type = NavType.LongType },
                    navArgument("startReturn") { type = NavType.BoolType; defaultValue = false },
                ),
            ) {
                DocumentDetailScreen(
                    onBack = { nav.popBackStack() }, onOpen = { nav.navigate("document/$it") },
                    onEditSale = { nav.navigate(SALE_ROUTE) { launchSingleTop = true } },
                )
            }
        }
    }
}
