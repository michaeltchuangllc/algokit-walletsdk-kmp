package com.michaeltchuang.walletsdk.ui.onboarding.screens

import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.Res
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.add_a_new_account
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.add_a_new_account_desc
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.add_a_wallet_or_account
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.because_you_have_already
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.by_creating_account
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.create_a_new_account
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.create_a_new_algorand_account_with
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_eye
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_hd_wallet
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_info
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_key
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.ic_wallet
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.import_an_account
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.import_an_existing
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.monitor_an_algorand_address
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.pera_icon_3d
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.privacy_policy
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.terms_and_conditions
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.warning
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.watch_an_address
import algokit_walletsdk_kmp.wallet_sdk_ui.generated.resources.welcome_to_pera
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import com.final_class.webview_multiplatform_mobile.webview.WebViewPlatform
import com.final_class.webview_multiplatform_mobile.webview.controller.rememberWebViewController
import com.michaeltchuang.walletsdk.core.foundation.utils.Log
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.PRIVACY_POLICY_URL
import com.michaeltchuang.walletsdk.core.foundation.utils.WalletSdkConstants.TERMS_AND_SERVICES_URL
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme
import com.michaeltchuang.walletsdk.ui.base.designsystem.theme.AlgoKitTheme.typography
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.GroupChoiceWidget
import com.michaeltchuang.walletsdk.ui.base.designsystem.widget.icon.AlgoKitIcon
import com.michaeltchuang.walletsdk.ui.base.navigation.AlgoKitScreens
import com.michaeltchuang.walletsdk.ui.onboarding.viewmodels.OnboardingAccountTypeViewModel
import com.michaeltchuang.walletsdk.ui.settings.domain.localization.localizedStringResource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.vectorResource
import org.koin.compose.viewmodel.koinViewModel

private const val TAG = "OnboardingAccountTypeScreen"

@Composable
fun OnboardingAccountTypeScreen(
    navController: NavHostController,
    onClick: (message: String) -> Unit,
) {
    val viewModel: OnboardingAccountTypeViewModel = koinViewModel()
    val scope = rememberCoroutineScope()
    val viewState = viewModel.state.collectAsStateWithLifecycle().value
    LaunchedEffect(Unit) {
        viewModel.viewEvent.collect {
            when (it) {
                is OnboardingAccountTypeViewModel.ViewEvent.AccountCreated -> {
                    navController.navigate(AlgoKitScreens.CREATE_ACCOUNT_NAME.name)
                    Log.d(TAG, it.accountCreation.address)
                }

                is OnboardingAccountTypeViewModel.ViewEvent.Error -> {
                    Log.d(TAG, it.message)
                }
            }
        }
    }
    when (viewState) {
        is OnboardingAccountTypeViewModel.ViewState.Idle -> {}
        is OnboardingAccountTypeViewModel.ViewState.Loading -> LoadingState()
        is OnboardingAccountTypeViewModel.ViewState.Content ->
            ContentState(
                hasWalletWithNoAccounts = viewState.hasWalletWithNoAccounts,
                viewModel = viewModel,
                scope = scope,
                navController = navController,
                onClick = onClick,
            )
    }
}

@Composable
private fun ContentState(
    hasWalletWithNoAccounts: Boolean,
    viewModel: OnboardingAccountTypeViewModel,
    scope: CoroutineScope,
    navController: NavHostController,
    onClick: (message: String) -> Unit,
) {
    Column(
        modifier =
            Modifier
                .background(color = AlgoKitTheme.colors.background)
                .fillMaxWidth()
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Top,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                style = typography.title.regular.sansMedium,
                color = AlgoKitTheme.colors.textMain,
                text =
                    localizedStringResource(
                        Res.string.welcome_to_pera,
                    ),
            )
            Spacer(modifier = Modifier.weight(1f))
            AlgoKitIcon(
                painter = painterResource(Res.drawable.pera_icon_3d),
                contentDescription = localizedStringResource(Res.string.add_a_wallet_or_account),
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        if (hasWalletWithNoAccounts) {
            CreateNewAccountCard {
                navController.navigate(AlgoKitScreens.FALCON24_WALLET_SELECTION_SCREEN.name)
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        CreateWalletHdWidget(viewModel, scope)
        ImportHdWalletWidget(navController)
        WatchAddressWidget(navController)
        Spacer(modifier = Modifier.weight(1f))
        TermsAndPrivacy()
    }
}

@Composable
private fun CreateWalletHdWidget(
    viewModel: OnboardingAccountTypeViewModel,
    scope: CoroutineScope,
) {
    GroupChoiceWidget(
        title = localizedStringResource(Res.string.create_a_new_account),
        description = localizedStringResource(Res.string.create_a_new_algorand_account_with),
        icon = vectorResource(Res.drawable.ic_wallet),
        iconContentDescription = localizedStringResource(Res.string.create_a_new_algorand_account_with),
        onClick = {
            scope.launch {
                viewModel.createFalcon24Account()
            }
        },
    )
}

@Composable
private fun ImportHdWalletWidget(navController: NavController) {
    GroupChoiceWidget(
        title = localizedStringResource(Res.string.import_an_account),
        description = localizedStringResource(Res.string.import_an_existing),
        iconContentDescription = localizedStringResource(Res.string.import_an_existing),
        icon = vectorResource(Res.drawable.ic_key),
        onClick = { navController.navigate(AlgoKitScreens.ACCOUNT_RECOVERY_TYPE_SCREEN.name) },
    )
}

@Suppress("LongMethod")
@Composable
fun CreateNewAccountCard(
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    val icon = vectorResource(Res.drawable.ic_hd_wallet)
    val outlineColor = AlgoKitTheme.colors.wallet3IconGovernor
    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)

    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) }
            .drawBehind {
                drawRoundRect(
                    color = outlineColor,
                    size = Size(size.width, size.height),
                    cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx()),
                    style = Stroke(width = 2.dp.toPx(), pathEffect = dashEffect),
                )
            },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                modifier =
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(AlgoKitTheme.colors.layerGrayLighter)
                        .padding(8.dp),
                imageVector = icon,
                contentDescription = localizedStringResource(Res.string.add_a_new_account_desc),
                tint = AlgoKitTheme.colors.textMain,
            )

            Spacer(Modifier.width(24.dp))
            Column {
                Text(
                    style = typography.body.regular.sansMedium,
                    color = AlgoKitTheme.colors.textMain,
                    text = localizedStringResource(Res.string.add_a_new_account),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    style = typography.footnote.sans,
                    color = AlgoKitTheme.colors.textGray,
                    text = localizedStringResource(Res.string.add_a_new_account_desc),
                )
            }
        }

        Row(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-12).dp)
                .background(AlgoKitTheme.colors.background)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AlgoKitIcon(
                painter = painterResource(Res.drawable.ic_info),
                contentDescription = localizedStringResource(Res.string.warning),
                modifier = Modifier.size(20.dp),
                tintColor = AlgoKitTheme.colors.wallet3IconGovernor,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = localizedStringResource(Res.string.because_you_have_already),
                style = typography.footnote.sansMedium,
                color = AlgoKitTheme.colors.wallet3IconGovernor,
            )
        }
    }
}

@Composable
private fun WatchAddressWidget(navController: NavController) {
    GroupChoiceWidget(
        title = localizedStringResource(Res.string.watch_an_address),
        description = localizedStringResource(Res.string.monitor_an_algorand_address),
        iconContentDescription = localizedStringResource(Res.string.monitor_an_algorand_address),
        icon = vectorResource(Res.drawable.ic_eye),
        onClick = { navController.navigate(AlgoKitScreens.CREATE_WATCH_ACCOUNT_SCREEN.name) },
    )
}

@Composable
fun TermsAndPrivacy(modifier: Modifier = Modifier) {
    val layoutResult =
        remember {
            mutableStateOf<TextLayoutResult?>(null)
        }
    val annotatedString = createAnnotatedString()
    val webViewController by rememberWebViewController()
    WebViewPlatform(webViewController = webViewController)
    Text(
        style = typography.footnote.sans,
        color = AlgoKitTheme.colors.textGray,
        modifier =
            modifier
                .pointerInput(annotatedString) {
                    detectTapGestures { pos ->
                        layoutResult.value?.let { layoutResult ->
                            // Adjust the position to account for padding
                            val adjustedPos =
                                pos.copy(x = pos.x - 43.dp.toPx(), y = pos.y - 24.dp.toPx())
                            val offset = layoutResult.getOffsetForPosition(adjustedPos)
                            annotatedString
                                .getStringAnnotations(
                                    tag = "TERMS_AND_CONDITIONS",
                                    start = offset,
                                    end = offset + 1,
                                ).firstOrNull()
                                ?.let {
                                    webViewController.open(TERMS_AND_SERVICES_URL)
                                }
                            annotatedString
                                .getStringAnnotations(
                                    tag = "PRIVACY_POLICY",
                                    start = offset,
                                    end = offset + 1,
                                ).firstOrNull()
                                ?.let {
                                    webViewController.open(PRIVACY_POLICY_URL)
                                }
                        }
                    }
                }.padding(horizontal = 43.dp, vertical = 24.dp),
        text = annotatedString,
        onTextLayout = {
            layoutResult.value = it
        },
    )
}

@Composable
private fun createAnnotatedString() =
    buildAnnotatedString {
        val fullText =
            localizedStringResource(
                Res.string.by_creating_account,
            )
        val termsAndConditionsText = localizedStringResource(Res.string.terms_and_conditions)
        val privacyPolicyText = localizedStringResource(Res.string.privacy_policy)

        val termsAndConditionsStartIndex = fullText.indexOf(termsAndConditionsText)
        val termsAndConditionsEndIndex = termsAndConditionsStartIndex + termsAndConditionsText.length
        val privacyPolicyStartIndex = fullText.indexOf(privacyPolicyText)
        val privacyPolicyEndIndex = privacyPolicyStartIndex + privacyPolicyText.length

        append(fullText)

        addStyle(
            style =
                SpanStyle(
                    color = AlgoKitTheme.colors.linkPrimary,
                ),
            start = termsAndConditionsStartIndex,
            end = termsAndConditionsEndIndex,
        )
        addStringAnnotation(
            tag = "TERMS_AND_CONDITIONS",
            annotation = TERMS_AND_SERVICES_URL,
            start = termsAndConditionsStartIndex,
            end = termsAndConditionsEndIndex,
        )

        addStyle(
            style =
                SpanStyle(
                    color = AlgoKitTheme.colors.linkPrimary,
                ),
            start = privacyPolicyStartIndex,
            end = privacyPolicyEndIndex,
        )
        addStringAnnotation(
            tag = "PRIVACY_POLICY",
            annotation = PRIVACY_POLICY_URL,
            start = privacyPolicyStartIndex,
            end = privacyPolicyEndIndex,
        )
    }

@Composable
private fun LoadingState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}
