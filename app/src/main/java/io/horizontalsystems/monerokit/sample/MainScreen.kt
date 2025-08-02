package io.horizontalsystems.monerokit.sample

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController

@Composable
fun MainScreen(navController: NavHostController) {
    val viewModel = viewModel<MainViewModel>()
    val uiState = viewModel.uiState
    Scaffold {
        Column(
            modifier = Modifier.padding(it)
        ) {
            var currentPage by remember { mutableStateOf(Page.Balance) }

            TabRow(selectedTabIndex = currentPage.ordinal) {
                Page.entries.forEach { page ->
                    Tab(
                        selected = currentPage == page,
                        onClick = { currentPage = page },
                        text = {
                            Text(
                                text = page.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }

            Crossfade(targetState = currentPage, label = "") {
                when (it) {
                    Page.Balance -> BalanceScreen(viewModel, uiState, navController)
                    Page.Transactions -> {
//                        TransactionsScreen()
                    }

                    Page.Send -> {
//                        SendScreen(StellarAsset.Native)
                    }
                }
            }
        }
    }
}

enum class Page {
    Balance, Transactions, Send
}
