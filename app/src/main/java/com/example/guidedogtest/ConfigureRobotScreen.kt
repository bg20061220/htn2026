package com.example.guidedogtest

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** The commands whose wheel speeds are worth tuning, in the order the page lists them. */
private val TUNED_COMMANDS = listOf("FORWARD", "LEFT", "RIGHT")

/**
 * Configure Robot: the manual drive buttons and the wheel values behind them.
 *
 * This page exists to be used on the floor. The phone is mounted on the car and nobody reads it while
 * the robot walks, so the manual controls are not part of the demo flow - they are the test rig: set
 * a wheel pair, press its command, watch what the chassis does, adjust. The values are saved as they
 * are typed, so a tuning session survives closing the app.
 *
 * It also carries the **compass calibration readout**: the raw phone heading, the corrected robot
 * heading and the mounting offset between them. That readout is what makes the one-line
 * `ROBOT_HEADING_OFFSET_DEGREES` constant in `Heading.kt` checkable on the robot: point the robot at
 * a heading you know, and if the corrected value is 180 degrees out, the sign is the wrong way.
 *
 * A command stays latched until another one is pressed - including across this page and the controls
 * screen - which is what makes one-handed tuning possible: set the numbers while the car is moving,
 * and press STOP when it is where you want it.
 */
@Composable
fun ConfigureRobotScreen(
    settings: MotorSettings,
    onSettingsChange: (MotorSettings) -> Unit,
    rawHeading: Double?,
    robotHeading: Double?,
    offsetDegrees: Double,
    command: String,
    onCommand: (String) -> Unit,
    connected: Boolean,
    linkStatus: String,
    onConnect: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Configure Robot", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) { Text("BACK") }
        }

        Text("ESP32: $linkStatus")

        // The commands only reach the car through an open link, so the page that drives it can also
        // open one. Saying so beats the silence of a button that looks pressed and does nothing.
        if (!connected) {
            Text(
                text = "Connect the robot to drive",
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(modifier = Modifier.height(4.dp))
            Button(onClick = onConnect) { Text("CONNECT ROBOT") }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text("Robot Command: $command")

        Spacer(modifier = Modifier.height(16.dp))

        // Compass calibration readout: the raw phone heading, the corrected robot heading, and the
        // mounting offset between them. Point the robot at a known heading and compare - if the
        // corrected value is 180 degrees out, the sign of ROBOT_HEADING_OFFSET_DEGREES is the wrong way.
        Text("Compass", style = MaterialTheme.typography.labelLarge)
        Text("raw phone heading:  ${headingText(rawHeading)}", style = MaterialTheme.typography.bodySmall)
        Text("robot heading:      ${headingText(robotHeading)}", style = MaterialTheme.typography.bodySmall)
        Text(
            "mounting offset:    ${signedDegrees(offsetDegrees)}",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Command", modifier = Modifier.weight(1.4f), style = MaterialTheme.typography.labelLarge)
            Text("Left PWM", modifier = Modifier.weight(0.9f), style = MaterialTheme.typography.labelLarge)
            Text("Right PWM", modifier = Modifier.weight(0.9f), style = MaterialTheme.typography.labelLarge)
        }

        TUNED_COMMANDS.forEach { name ->
            val speeds = settings.speedsFor(name)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = { onCommand(name) },
                    modifier = Modifier.weight(1.4f),
                    // The default button padding wraps "FORWARD" onto two lines in this column.
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    Text(name, maxLines = 1)
                }

                PwmField(
                    value = speeds.left,
                    onValue = { onSettingsChange(settings.withSpeeds(name, speeds.copy(left = it))) },
                    modifier = Modifier.weight(0.9f),
                )

                PwmField(
                    value = speeds.right,
                    onValue = { onSettingsChange(settings.withSpeeds(name, speeds.copy(right = it))) },
                    modifier = Modifier.weight(0.9f),
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // STOP has no values to tune: it is both wheels released, and it has to stay that.
        Button(
            onClick = { onCommand("STOP") },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("STOP")
        }
    }
}

/** "+90°" or "-90°" - the sign is the whole point of this number, so it is always shown. */
private fun signedDegrees(degrees: Double): String =
    (if (degrees < 0) "-" else "+") + abs(degrees).toInt() + "°"

/**
 * A signed PWM entry.
 *
 * The text is kept as typed, so a lone "-" or an emptied field does not fight the typist; only
 * something that parses and fits the PWM range reaches the settings, and the field is pulled back
 * into line whenever the stored value changes underneath it.
 */
@Composable
private fun PwmField(
    value: Int,
    onValue: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by remember { mutableStateOf(value.toString()) }

    LaunchedEffect(value) {
        if (text.toIntOrNull() != value) text = value.toString()
    }

    OutlinedTextField(
        value = text,
        onValueChange = { typed ->
            val cleaned = typed
                .filterIndexed { index, c -> c.isDigit() || (c == '-' && index == 0) }
                .take(5)
            text = cleaned
            cleaned.toIntOrNull()?.let { onValue(it.coerceIn(-Drive.MAX_PWM, Drive.MAX_PWM)) }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        singleLine = true,
        modifier = modifier,
    )
}
