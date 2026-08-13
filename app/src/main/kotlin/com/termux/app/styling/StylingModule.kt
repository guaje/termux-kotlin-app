package com.termux.app.styling

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for Termux:Styling components.
 */
@Module
@InstallIn(SingletonComponent::class)
object StylingModule {

    @Provides
    @Singleton
    fun provideNerdFontArchiveProvider(): NerdFontArchiveProvider = HttpNerdFontArchiveProvider()

    @Provides
    @Singleton
    fun provideNerdFontDownloadClient(
        @ApplicationContext context: Context,
        archiveProvider: NerdFontArchiveProvider
    ): NerdFontDownloadClient = NerdFontDownloader(context, archiveProvider)

    @Provides
    @Singleton
    fun provideFontManager(
        @ApplicationContext context: Context
    ): FontManager {
        return FontManager(context)
    }
    
    @Provides
    @Singleton
    fun provideStylingManager(
        @ApplicationContext context: Context,
        fontManager: FontManager,
        nerdFontDownloadClient: NerdFontDownloadClient
    ): StylingManager {
        return StylingManager(context, fontManager, nerdFontDownloadClient)
    }
}
