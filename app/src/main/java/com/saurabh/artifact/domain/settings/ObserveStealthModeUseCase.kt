package com.saurabh.artifact.domain.settings

import com.saurabh.artifact.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class ObserveStealthModeUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository
) {
    operator fun invoke(): Flow<Boolean> {
        return settingsRepository.userSettings.map { it.stealthModeEnabled }
    }
}
