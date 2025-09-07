package com.noahlangat.relay.di

import android.content.Context
import com.noahlangat.relay.bluetooth.BluetoothManager
import com.noahlangat.relay.bluetooth.GamepadInputHandler
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    @Provides
    @Singleton
    fun provideBluetoothManager(@ApplicationContext context: Context): BluetoothManager {
        return BluetoothManager(context)
    }
    
    @Provides
    @Singleton
    fun provideGamepadInputHandler(): GamepadInputHandler {
        return GamepadInputHandler()
    }
}