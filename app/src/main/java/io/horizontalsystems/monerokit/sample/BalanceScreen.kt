package io.horizontalsystems.monerokit.sample

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController

@Composable
fun BalanceScreen(viewModel: MainViewModel, uiState: MainUiState, navController: NavHostController) {
    val address = viewModel.address

    SelectionContainer {
        Column {
            Text(text = "Address: $address")
            Text(text = "Balance: ${uiState.totalBalance}")
            Text(text = "Sync State: ${uiState.syncState}")

            Spacer(modifier = Modifier.height(20.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Button(onClick = viewModel::start) {
                    Text(text = "start")
                }
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = viewModel::stop) {
                    Text(text = "Stop")
                }
            }
        }
    }
}