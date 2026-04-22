<?php

use Azuriom\Plugin\NestworldAnalytics\Controllers\Admin\AnalyticsController;
use Illuminate\Support\Facades\Route;

Route::get('/', [AnalyticsController::class, 'index'])->name('index');
Route::post('/anomalies/{id}/resolve', [AnalyticsController::class, 'resolveAnomaly'])->name('anomalies.resolve');
