package com.michaeltchuang.walletsdk.demo.ui.navigation

import algokit_walletsdk_kmp.composedemoapp.generated.resources.Res
import algokit_walletsdk_kmp.composedemoapp.generated.resources.ic_home
import algokit_walletsdk_kmp.composedemoapp.generated.resources.ic_tv
import algokit_walletsdk_kmp.composedemoapp.generated.resources.nav_broadcast
import algokit_walletsdk_kmp.composedemoapp.generated.resources.nav_home
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource

@Composable
fun AppNavigationBar(
    navController: NavController,
    displayCoreActionsBottomSheet: () -> Unit,
) {
    val currentDestination = navController.currentBackStackEntryAsState().value?.destination
    NavigationBar(
        containerColor = AlgoKitTheme.colors.tabBarBackground,
    ) {
        topLevelRoutes.forEachIndexed { _, navigationItem ->
            NavigationBarItem(
                selected = navigationItem::class.qualifiedName == currentDestination?.route,
                label = {
                    (navigationItem.type as? TopLevelRoute.Type.NavButton)?.let {
                        Text(localizedStringResource(it.labelResource))
                    }
                },
                icon = {
                    Icon(
                        painter = painterResource(navigationItem.details.icon),
                        contentDescription = "",
                    )
                },
                onClick = {
                    when (navigationItem.type) {
                        TopLevelRoute.Type.CircularButton -> displayCoreActionsBottomSheet()
                        is TopLevelRoute.Type.NavButton -> {
                            navController.navigate(navigationItem) {
                                popUpTo(navController.graph.findStartDestination().navigatorName) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                        else -> { /* no-op */ }
                    }
                },
                colors =
                    NavigationBarItemDefaults.colors().copy(
                        selectedTextColor = AlgoKitTheme.colors.tabBarIconActive,
                        unselectedTextColor = AlgoKitTheme.colors.tabBarIconNonActive,
                        selectedIconColor = AlgoKitTheme.colors.tabBarIconActive,
                        unselectedIconColor = AlgoKitTheme.colors.tabBarIconNonActive,
                        selectedIndicatorColor = Color.Transparent,
                    ),
            )
        }
    }
}

data class TopLevelRouteDetails<T : Any>(
    val name: String,
    val route: T,
    val icon: DrawableResource,
)

private val topLevelRoutes: List<TopLevelRoute> = listOf(Accounts, Broadcast)

sealed interface TopLevelRoute {
    val type: Type
    val details: TopLevelRouteDetails<*>

    sealed interface Type {
        data class NavButton(
            val labelResource: StringResource,
        ) : Type

        data object CircularButton : Type
    }
}

@Serializable
data object Accounts : TopLevelRoute {
    override val type: TopLevelRoute.Type = TopLevelRoute.Type.NavButton(Res.string.nav_home)
    override val details =
        TopLevelRouteDetails(
            name = "Accounts",
            route = this,
            icon = Res.drawable.ic_home,
        )
}

@Serializable
data object Broadcast : TopLevelRoute {
    override val type: TopLevelRoute.Type = TopLevelRoute.Type.NavButton(Res.string.nav_broadcast)
    override val details =
        TopLevelRouteDetails(
            name = "Broadcast",
            route = this,
            icon = Res.drawable.ic_tv,
        )
}

