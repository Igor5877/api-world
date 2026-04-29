<?php

use Azuriom\Plugin\NestworldAnalytics\Controllers\Admin\AnalyticsController;
use Azuriom\Plugin\NestworldAnalytics\Controllers\Admin\SettingsController;
use Illuminate\Support\Facades\Route;

Route::get('/', [AnalyticsController::class, 'index'])->name('index');
Route::post('/sync', [AnalyticsController::class, 'sync'])->name('sync');
Route::post('/anomalies/{id}/resolve', [AnalyticsController::class, 'resolveAnomaly'])->name('anomalies.resolve');

Route::get('/settings', [SettingsController::class, 'index'])->name('settings');
Route::post('/settings', [SettingsController::class, 'update'])->name('settings.update');
