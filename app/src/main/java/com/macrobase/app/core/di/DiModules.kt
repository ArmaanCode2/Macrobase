package com.macrobase.app.core.di

import com.macrobase.app.data.database.BuiltInDatabaseManager
import com.macrobase.app.data.database.UserDatabase
import com.macrobase.app.data.provider.FoodDataProvider
import com.macrobase.app.data.provider.LocalFoodDatabaseProvider
import com.macrobase.app.data.repository.DiaryRepositoryImpl
import com.macrobase.app.data.repository.FoodRepositoryImpl
import com.macrobase.app.data.repository.GoalsRepositoryImpl
import com.macrobase.app.data.repository.PreferencesRepositoryImpl
import com.macrobase.app.data.repository.RecipeRepositoryImpl
import com.macrobase.app.data.repository.StatisticsRepositoryImpl
import com.macrobase.app.data.repository.WaterRepositoryImpl
import com.macrobase.app.data.repository.WeightRepositoryImpl
import com.macrobase.app.domain.repository.DiaryRepository
import com.macrobase.app.domain.repository.FoodRepository
import com.macrobase.app.domain.repository.GoalsRepository
import com.macrobase.app.domain.repository.PreferencesRepository
import com.macrobase.app.domain.repository.RecipeRepository
import com.macrobase.app.domain.repository.StatisticsRepository
import com.macrobase.app.domain.repository.WaterRepository
import com.macrobase.app.domain.repository.WeightRepository
import com.macrobase.app.domain.usecase.AddWaterEntryUseCase
import com.macrobase.app.domain.usecase.AddWeightEntryUseCase
import com.macrobase.app.domain.usecase.CalculateNutritionForServingUseCase
import com.macrobase.app.domain.usecase.DeleteDiaryEntryUseCase
import com.macrobase.app.domain.usecase.DeleteWaterEntryUseCase
import com.macrobase.app.domain.usecase.DeleteWeightEntryUseCase
import com.macrobase.app.domain.usecase.GetCalendarAdherenceUseCase
import com.macrobase.app.domain.usecase.GetDailyDiaryUseCase
import com.macrobase.app.domain.usecase.GetDailyNutritionSummaryUseCase
import com.macrobase.app.domain.usecase.GetDiaryEntryUseCase
import com.macrobase.app.domain.usecase.GetFoodDetailsUseCase
import com.macrobase.app.domain.usecase.GetFoodServingsUseCase
import com.macrobase.app.domain.usecase.GetGoalsUseCase
import com.macrobase.app.domain.usecase.GetStatisticsUseCase
import com.macrobase.app.domain.usecase.GetWaterEntriesUseCase
import com.macrobase.app.domain.usecase.GetWaterForDateUseCase
import com.macrobase.app.domain.usecase.GetWaterHistoryUseCase
import com.macrobase.app.domain.usecase.GetWeightForDateUseCase
import com.macrobase.app.domain.usecase.GetWeightHistoryUseCase
import com.macrobase.app.domain.usecase.LogFoodUseCase
import com.macrobase.app.domain.usecase.SearchFoodsUseCase
import com.macrobase.app.domain.usecase.UpdateDiaryEntryUseCase
import com.macrobase.app.domain.usecase.UpdateGoalsUseCase
import com.macrobase.app.domain.usecase.UpdateWaterEntryUseCase
import com.macrobase.app.data.repository.PortabilityRepositoryImpl
import com.macrobase.app.domain.repository.PortabilityRepository
import com.macrobase.app.domain.usecase.ExportUserDataUseCase
import com.macrobase.app.domain.usecase.ImportUserDataUseCase
import com.macrobase.app.domain.usecase.ValidateBackupUseCase
import com.macrobase.app.feature.calendar.CalendarViewModel
import com.macrobase.app.feature.customfoods.CustomFoodsViewModel
import com.macrobase.app.feature.dashboard.HomeViewModel
import com.macrobase.app.feature.detail.FoodDetailViewModel
import com.macrobase.app.feature.goals.DailyGoalsViewModel
import com.macrobase.app.feature.importexport.ImportExportViewModel
import com.macrobase.app.feature.preferences.PreferencesViewModel
import com.macrobase.app.feature.recipes.RecipesViewModel
import com.macrobase.app.feature.search.SearchViewModel
import com.macrobase.app.feature.scanner.ImagePreprocessor
import com.macrobase.app.feature.scanner.ImageQualityChecker
import com.macrobase.app.feature.scanner.NutritionLabelOcrEngine
import com.macrobase.app.feature.scanner.NutritionLabelParser
import com.macrobase.app.feature.scanner.NutritionLabelScannerViewModel
import com.macrobase.app.feature.statistics.StatisticsViewModel
import com.macrobase.app.feature.water.WaterViewModel
import com.macrobase.app.feature.weight.WeightViewModel
import com.macrobase.app.domain.usecase.basket.AddFoodToBasketUseCase
import com.macrobase.app.domain.usecase.basket.GetBasketItemsUseCase
import com.macrobase.app.domain.usecase.basket.RemoveBasketItemUseCase
import com.macrobase.app.domain.usecase.basket.UpdateBasketItemUseCase
import com.macrobase.app.domain.usecase.basket.UpdateAllBasketItemsUseCase
import com.macrobase.app.domain.usecase.basket.ClearBasketUseCase
import com.macrobase.app.domain.usecase.basket.CommitBasketUseCase
import com.macrobase.app.domain.usecase.basket.CommitSingleBasketItemUseCase
import com.macrobase.app.feature.basket.BasketViewModel
import com.macrobase.app.domain.repository.rank.RankRepository
import com.macrobase.app.data.repository.rank.RankRepositoryImpl
import com.macrobase.app.domain.usecase.rank.GetRankProfileUseCase
import com.macrobase.app.domain.usecase.rank.CalculateLifestyleScoreUseCase
import com.macrobase.app.feature.rank.RankViewModel
import org.koin.core.module.dsl.singleOf
import org.koin.core.module.dsl.factoryOf
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val databaseModule = module {
    single { UserDatabase.create(get()) }

    single { get<UserDatabase>().diaryDao() }
    single { get<UserDatabase>().customFoodDao() }
    single { get<UserDatabase>().recipeDao() }
    single { get<UserDatabase>().weightDao() }
    single { get<UserDatabase>().waterDao() }
    single { com.macrobase.app.data.repository.DiaryFoodLinkRepair(get()) }

    single { BuiltInDatabaseManager(get()) }
    single<FoodDataProvider> { LocalFoodDatabaseProvider(get()) }
}

val repositoryModule = module {
    // "Now" for every class that reads the date; tests pass a fixed clock instead
    single<java.time.Clock> { com.macrobase.app.core.util.DeviceClock }
    single<PreferencesRepository> { PreferencesRepositoryImpl(get()) }
    single<GoalsRepository> { GoalsRepositoryImpl(get()) }
    single<DiaryRepository> { DiaryRepositoryImpl(get(), get(), get(), get()) }
    single<FoodRepository> { FoodRepositoryImpl(get(), get()) }
    single<RecipeRepository> { RecipeRepositoryImpl(get()) }
    single<WeightRepository> { WeightRepositoryImpl(get()) }
    single<WaterRepository> { WaterRepositoryImpl(get()) }
    single<StatisticsRepository> { StatisticsRepositoryImpl(get(), get(), get(), get()) }
    single<PortabilityRepository> { PortabilityRepositoryImpl(get(), get(), get()) }
    // Saved to the app's private files so staged items survive the process being killed (BUG-015)
    single<com.macrobase.app.domain.repository.basket.BasketRepository> {
        com.macrobase.app.data.repository.basket.PersistentBasketRepository(
            file = java.io.File(get<android.content.Context>().filesDir, "basket.json"),
            scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        )
    }
    single<RankRepository> { RankRepositoryImpl(get(), get(), get(), get(), get(), get()) }
}

val useCaseModule = module {
    singleOf(::SearchFoodsUseCase)
    singleOf(::GetFoodDetailsUseCase)
    singleOf(::GetFoodServingsUseCase)
    singleOf(::CalculateNutritionForServingUseCase)
    singleOf(::GetDailyDiaryUseCase)
    singleOf(::GetDailyNutritionSummaryUseCase)
    singleOf(::LogFoodUseCase)
    singleOf(::UpdateDiaryEntryUseCase)
    singleOf(::DeleteDiaryEntryUseCase)
    singleOf(::GetDiaryEntryUseCase)
    singleOf(::GetCalendarAdherenceUseCase)
    singleOf(::GetWeightHistoryUseCase)
    singleOf(::GetWeightForDateUseCase)
    singleOf(::AddWeightEntryUseCase)
    singleOf(::DeleteWeightEntryUseCase)
    singleOf(::GetWaterForDateUseCase)
    singleOf(::GetWaterEntriesUseCase)
    singleOf(::AddWaterEntryUseCase)
    singleOf(::UpdateWaterEntryUseCase)
    singleOf(::DeleteWaterEntryUseCase)
    singleOf(::GetWaterHistoryUseCase)
    singleOf(::GetGoalsUseCase)
    singleOf(::UpdateGoalsUseCase)
    singleOf(::GetStatisticsUseCase)
    singleOf(::ExportUserDataUseCase)
    singleOf(::ValidateBackupUseCase)
    singleOf(::ImportUserDataUseCase)

    // Scanner & OCR
    singleOf(::ImageQualityChecker)
    singleOf(::ImagePreprocessor)
    singleOf(::NutritionLabelParser)
    
    // Basket
    singleOf(::AddFoodToBasketUseCase)
    singleOf(::GetBasketItemsUseCase)
    singleOf(::RemoveBasketItemUseCase)
    singleOf(::UpdateBasketItemUseCase)
    singleOf(::UpdateAllBasketItemsUseCase)
    singleOf(::ClearBasketUseCase)
    singleOf(::CommitBasketUseCase)
    singleOf(::CommitSingleBasketItemUseCase)

    // Rank
    singleOf(::GetRankProfileUseCase)
    singleOf(::CalculateLifestyleScoreUseCase)
}

val viewModelModule = module {
    viewModelOf(::HomeViewModel)
    viewModelOf(::SearchViewModel)
    viewModelOf(::FoodDetailViewModel)
    viewModelOf(::CalendarViewModel)
    viewModelOf(::WeightViewModel)
    viewModelOf(::WaterViewModel)
    viewModelOf(::StatisticsViewModel)
    viewModelOf(::CustomFoodsViewModel)
    viewModelOf(::RecipesViewModel)
    viewModelOf(::DailyGoalsViewModel)
    viewModelOf(::PreferencesViewModel)
    viewModelOf(::ImportExportViewModel)
    viewModelOf(::NutritionLabelScannerViewModel)
    viewModelOf(::BasketViewModel)
    viewModelOf(::RankViewModel)
}

val appModules = listOf(
    databaseModule,
    repositoryModule,
    useCaseModule,
    viewModelModule
)
