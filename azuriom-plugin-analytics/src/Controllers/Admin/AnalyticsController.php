<?php

namespace Azuriom\Plugin\NestworldAnalytics\Controllers\Admin;

use Azuriom\Http\Controllers\Controller;
use Illuminate\Http\Request;
use Illuminate\Support\Carbon;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Http;

class AnalyticsController extends Controller
{
    private function api(): \Illuminate\Http\Client\PendingRequest
    {
        return Http::baseUrl(setting('nestworld_api_url', 'http://127.0.0.1:8000/api/v1'))
            ->timeout((int) setting('nestworld_api_timeout', 5))
            ->acceptJson();
    }

    private function getCache(string $key): array
    {
        $row = DB::table('nestworld_analytics_cache')->where('key', $key)->first();
        return $row ? (json_decode($row->data, true) ?? []) : [];
    }

    private function setCache(string $key, array $data): void
    {
        DB::table('nestworld_analytics_cache')->upsert(
            [['key' => $key, 'data' => json_encode($data), 'cached_at' => Carbon::now()]],
            ['key'],
            ['data', 'cached_at']
        );
    }

    private function lastSync(): ?Carbon
    {
        $row = DB::table('nestworld_analytics_cache')->orderBy('cached_at')->first();
        return $row ? Carbon::parse($row->cached_at) : null;
    }

    /**
     * Показує дані з локальної БД — без жодних запитів до API.
     */
    public function index(Request $request)
    {
        $hours = (int) $request->query('hours', 24);
        $hours = max(1, min(168, $hours));

        $overview   = $this->getCache('overview');
        $anomalies  = $this->getCache('anomalies');
        $topSellers = $this->getCache("top_sellers_{$hours}h");
        $topItems   = $this->getCache("items_{$hours}h");

        $totalVolume       = $overview['total_volume'] ?? null;
        $totalTransactions = $overview['total_transactions'] ?? null;
        $hourly            = $overview['hourly'] ?? [];

        $chartLabels = collect($hourly)->map(fn($h) => substr($h['hour'], 11, 5))->values();
        $chartVolume = collect($hourly)->map(fn($h) => round($h['volume'], 2))->values();

        $lastSync = $this->lastSync();

        return view('nestworld-analytics::admin.index', compact(
            'hours', 'totalVolume', 'totalTransactions',
            'anomalies', 'topSellers', 'topItems',
            'chartLabels', 'chartVolume', 'lastSync'
        ));
    }

    /**
     * Явна синхронізація з API — викликається кнопкою в адмін-панелі.
     */
    public function sync(Request $request)
    {
        $hours = (int) $request->query('hours', 24);
        $hours = max(1, min(168, $hours));

        try {
            $this->setCache('overview',              $this->api()->get('/analytics/overview')->json() ?? []);
            $this->setCache('anomalies',             $this->api()->get('/analytics/anomalies', ['resolved' => 'false'])->json() ?? []);
            $this->setCache("top_sellers_{$hours}h", $this->api()->get('/analytics/top-sellers', ['hours' => $hours])->json() ?? []);
            $this->setCache("items_{$hours}h",       $this->api()->get('/analytics/items', ['hours' => $hours])->json() ?? []);
        } catch (\Throwable $e) {
            return back()->with('error', trans('nestworld-analytics::messages.sync_error') . ': ' . $e->getMessage());
        }

        return redirect()->route('nestworld-analytics.admin.index', ['hours' => $hours])
            ->with('success', trans('nestworld-analytics::messages.sync_success'));
    }

    public function resolveAnomaly(int $id)
    {
        $this->api()->post("/analytics/anomalies/{$id}/resolve");

        // Скидаємо кеш аномалій
        DB::table('nestworld_analytics_cache')->where('key', 'anomalies')->delete();

        return back()->with('success', trans('nestworld-analytics::messages.anomaly_resolved'));
    }
}
