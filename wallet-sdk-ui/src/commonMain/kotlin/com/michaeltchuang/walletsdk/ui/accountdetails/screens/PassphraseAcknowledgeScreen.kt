package com.michaeltchuang.walletsdk.ui.accountdetails.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.cancel
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.confirm_and_acknowledge_the_following
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.i_risk_losing_all_my
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_account_rekeyed
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.keep_it_secret_keep_it
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.my_funds_are_permanently_lost
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.nobody_can_see_my_screen
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.pera_employees_will_never_ask
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.reveal_passphrase
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.navigation.compose.rememberNavController
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitButtonState
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitPrimaryButton
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.button.AlgoKitSecondaryButton
import com.michaeltchuang.walletsdk.ui.base.navigation.AlgoKitScreens
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.ui.tooling.preview.Preview

@Composable
fun PassphraseAcknowledgeScreen(
    navController: NavController,
    address: String,
) {
    val confirmationItems = remember { getConfirmationItems() }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    color = AlgoKitTheme.colors.background,
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(32.dp))
        TopIcon()
        Spacer(modifier = Modifier.height(20.dp))
        TitleText()
        Spacer(modifier = Modifier.height(12.dp))
        DescriptionText()
        ConfirmationItemList(confirmationItems)
        Spacer(modifier = Modifier.height(32.dp))
        ConfirmButton(navController, confirmationItems, address)
        Spacer(modifier = Modifier.height(12.dp))
        CancelButton { navController.popBackStack() }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun TopIcon() {
    Icon(
        modifier = Modifier.size(72.dp),
        painter = painterResource(Res.drawable.ic_account_rekeyed),
        contentDescription = null,
        tint = AlgoKitTheme.colors.positive,
    )
}

@Composable
private fun TitleText() {
    Text(
        text = localizedStringResource(Res.string.keep_it_secret_keep_it),
        style = AlgoKitTheme.typography.body.large.sansMedium,
        color = AlgoKitTheme.colors.textMain,
    )
}

@Composable
private fun DescriptionText() {
    Text(
        text = localizedStringResource(Res.string.confirm_and_acknowledge_the_following),
        style = AlgoKitTheme.typography.body.regular.sans,
        color = AlgoKitTheme.colors.textMain,
    )
}

@Composable
private fun ConfirmButton(
    navController: NavController,
    confirmationItems: SnapshotStateList<ConfirmationItem>,
    address: String,
) {
    val isConfirmButtonEnabled by remember(confirmationItems) {
        derivedStateOf {
            confirmationItems.all { it.isChecked }
        }
    }
    AlgoKitPrimaryButton(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        state = if (isConfirmButtonEnabled) AlgoKitButtonState.ENABLED else AlgoKitButtonState.DISABLED,
        text = localizedStringResource(Res.string.reveal_passphrase),
        onClick = {
            navController.navigate(AlgoKitScreens.VIEW_PASSPHRASE_SCREEN.name)
        },
    )
}

@Composable
private fun CancelButton(onClick: () -> Unit) {
    AlgoKitSecondaryButton(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        text = localizedStringResource(Res.string.cancel),
        onClick = onClick,
    )
}

@Composable
private fun ConfirmationItemList(confirmationItems: SnapshotStateList<ConfirmationItem>) {
    val density = LocalDensity.current
    var confirmationItemHeight by remember { mutableStateOf(0.dp) }
    LazyColumn(
        verticalArrangement = Arrangement.Center,
    ) {
        itemsIndexed(confirmationItems) { index, item ->
            ConfirmationCheckbox(
                modifier = Modifier.defaultMinSize(minHeight = confirmationItemHeight),
                item = item,
                onCheckChange = { isChecked ->
                    confirmationItems[index] = item.copy(isChecked = isChecked)
                },
                onHeightChanged = { height ->
                    val heightDp = with(density) { height.toDp() }
                    if (confirmationItemHeight < heightDp) {
                        confirmationItemHeight = heightDp
                    }
                },
            )
            if (index < confirmationItems.lastIndex) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .height(1.dp)
                            .background(color = AlgoKitTheme.colors.layerGrayLighter),
                )
            }
        }
    }
}

@Composable
private fun ConfirmationCheckbox(
    modifier: Modifier,
    item: ConfirmationItem,
    onCheckChange: (Boolean) -> Unit,
    onHeightChanged: (Int) -> Unit,
) {
    Row(
        modifier = modifier.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            modifier =
                Modifier
                    .weight(1f)
                    .padding(start = 18.dp, end = 16.dp)
                    .onSizeChanged { onHeightChanged(it.height) },
            text = localizedStringResource(item.resId),
            style = AlgoKitTheme.typography.footnote.sans,
            color = AlgoKitTheme.colors.textMain,
        )
        PeraCheckbox(checked = item.isChecked) { onCheckChange(!item.isChecked) }
    }
}

@Composable
fun PeraCheckbox(
    modifier: Modifier = Modifier,
    checked: Boolean,
    interactionSource: MutableInteractionSource? = MutableInteractionSource(),
    enabled: () -> Boolean = { true },
    onCheckChanged: (Boolean) -> Unit,
) {
    Checkbox(
        interactionSource = interactionSource,
        modifier = modifier,
        colors =
            CheckboxDefaults.colors(
                checkmarkColor = AlgoKitTheme.colors.successCheckmark,
                checkedColor = AlgoKitTheme.colors.success,
                uncheckedColor = AlgoKitTheme.colors.layerGray,
            ),
        checked = checked,
        enabled = enabled(),
        onCheckedChange = onCheckChanged,
    )
}

private fun getConfirmationItems(): SnapshotStateList<ConfirmationItem> =
    mutableStateListOf(
        ConfirmationItem(Res.string.nobody_can_see_my_screen, isChecked = false),
        ConfirmationItem(resId = Res.string.i_risk_losing_all_my, isChecked = false),
        ConfirmationItem(resId = Res.string.my_funds_are_permanently_lost, isChecked = false),
        ConfirmationItem(resId = Res.string.pera_employees_will_never_ask, isChecked = false),
    )

private data class ConfirmationItem(
    val resId: StringResource,
    var isChecked: Boolean,
)

@Preview
@Composable
fun RecoveryPhraseCheckScreenPreview() {
    AlgoKitTheme {
        PassphraseAcknowledgeScreen(
            rememberNavController(),
            WalletSdkConstants.SAMPLE_FALCON24_ADDRESS,
        )
    }
}
