package io.horizontalsystems.monerokit.sample

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable

// Define a home route that doesn't take any arguments
@Serializable
object Home

@Serializable
data class AssetPage(val assetId: String)

@Composable
fun NavigationStack() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Home,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp)
    ) {
        composable<Home> {
            MainScreen(navController)
        }
//        composable<AssetPage> { backStackEntry ->
//            val assetPage = backStackEntry.toRoute<AssetPage>()
//
//            AssetScreen(assetPage.assetId)
//        }
    }
}
