package com.termux.app.di

import android.content.Context
import com.termux.app.core.deviceapi.actions.BatteryAction
import com.termux.app.core.deviceapi.actions.ClipboardAction
import com.termux.app.core.deviceapi.actions.TorchAction
import com.termux.app.core.deviceapi.actions.ToastAction
import com.termux.app.core.deviceapi.actions.VibrateAction
import com.termux.app.core.deviceapi.service.DeviceApiService
import com.termux.app.core.logging.TermuxLogger
import com.termux.app.pkg.cli.commands.device.DeviceCommands
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for Device API dependencies.
 *
 * Provides all device API actions and related services for dependency injection.
 * Each action follows the pattern: action class injects via constructor, then is
 * wired into the service/command dispatch in this module.
 */
@Module
@InstallIn(SingletonComponent::class)
object DeviceApiModule {

    // ========== API Actions ==========

    @Provides
    @Singleton
    fun provideBatteryAction(
        @ApplicationContext context: Context,
        logger: TermuxLogger
    ): BatteryAction {
        return BatteryAction(context, logger)
    }

    @Provides
    @Singleton
    fun provideClipboardAction(
        @ApplicationContext context: Context,
        logger: TermuxLogger
    ): ClipboardAction {
        return ClipboardAction(context, logger)
    }

    @Provides
    @Singleton
    fun provideVibrateAction(
        @ApplicationContext context: Context,
        logger: TermuxLogger
    ): VibrateAction {
        return VibrateAction(context, logger)
    }

    @Provides
    @Singleton
    fun provideToastAction(
        @ApplicationContext context: Context,
        logger: TermuxLogger
    ): ToastAction {
        return ToastAction(context, logger)
    }

    @Provides
    @Singleton
    fun provideTorchAction(
        @ApplicationContext context: Context,
        logger: TermuxLogger
    ): TorchAction {
        return TorchAction(context, logger)
    }

    // ========== CLI Commands ==========

    @Provides
    @Singleton
    fun provideDeviceCommands(
        batteryAction: BatteryAction,
        clipboardAction: ClipboardAction,
        vibrateAction: VibrateAction,
        toastAction: ToastAction,
        torchAction: TorchAction,
        logger: TermuxLogger
    ): DeviceCommands {
        return DeviceCommands(
            batteryAction,
            clipboardAction,
            vibrateAction,
            toastAction,
            torchAction,
            logger
        )
    }
}
