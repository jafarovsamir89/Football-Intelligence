package az.simplesoft.footballintelligence

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import az.simplesoft.footballintelligence.data.FootballScreenRepository
import az.simplesoft.footballintelligence.data.StoreFootballScreenRepository

class MainActivity:ComponentActivity() {
    private lateinit var screenRepository:FootballScreenRepository
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        screenRepository=StoreFootballScreenRepository(Store(this))
        FootballSyncScheduler.scheduleOnAppOpen(this)
        setContent { FootballScreen(screenRepository) }
    }
    override fun onDestroy() {
        super.onDestroy()
        screenRepository.close()
    }
}
