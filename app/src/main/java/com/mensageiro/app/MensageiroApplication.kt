package com.mensageiro.app

import android.app.Application

class MensageiroApplication : Application() {
    val container by lazy { AppContainer(this) }
}
