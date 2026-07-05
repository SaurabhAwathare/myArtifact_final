package com.saurabh.artifact.diagnostics

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticsModule {

    @Binds
    @Singleton
    abstract fun bindDiagnosticLogger(
        impl: LogcatDiagnosticLogger
    ): DiagnosticLogger
}
