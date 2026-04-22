<?php

namespace Azuriom\Plugin\NestworldAnalytics\Controllers\Admin;

use Azuriom\Http\Controllers\Controller;
use Illuminate\Http\Request;
use Illuminate\Support\Carbon;
use Illuminate\Support\Facades\DB;
use Illuminate\Support\Facades\Http;

class AnalyticsController extends Controller
{
    // Час життя кешу в секундах
    private const CACHE_TTL = 300;

    private function api(): \Illuminate\Http\Client\PendingRequest
    {
        return Http::baseUrl(config('nestworld-analytics.api_url'))
            ->timeout(config('nestworld-analytics.api_timeout', 5))
            ->acceptJson();
    }

    /**
     * Читає дані з локальної БД. Якщо кеш застарів — тягне з API і оновлює БД.
     */
    private function cached(string $key, callable $fetch): array
    {
        $row = DB::table('nestworld_analytics_cache')->where('key', $key)->first();

        if ($row && Carbon::parse($row->cached_at)->addSeconds(self::CACHE_TTL)->isFuture()) {
            return json_decode($row->data, true) ?? [];
        }

        // Кеш застарів або відсутній — запит до API
        try {
            $data = $fetch();
        } catch (\Throwable) {
            // API недоступний — повертаємо останній кеш (або порожньо)
            return $row ? (json_decode($row->data, true) ?? []) : [];
        }

        DB::table('nestworld_analytics_cache')->upsert(
            [['key' => $key, 'data' => json_encode($data), 'cached_at' => Carbon::now()]],
            ['key'],
            ['data', 'cached_at']
        );

        return $data;
    }

    public function index(Request $request)
    {
        $hours = (int) $request->query('hours', 24);
        $hours = max(1, min(168, $hours));

        $overview   = $this->cached('overview',              fn() => $this->api()->get('/analytics/overview')->json() ?? []);
        $anomalies  = $this->cached('anomalies',             fn() => $this->api()->get('/analytics/anomalies', ['resolved' => 'false'])->json() ?? []);
        $topSellers = $this->cached("top_sellers_{$hours}h", fn() => $this->api()->get('/analytics/top-sellers', ['hours' => $hours])->json() ?? []);
        $topItems   = $this->cached("items_{$hours}h",       fn() => $this->api()->get('/analytics/items', ['hours' => $hours])->json() ?? []);

        $totalVolume       = $overview['total_volume'] ?? 0;
        $totalTransactions = $overview['total_transactions'] ?? 0;
        $hourly            = $overview['hourly'] ?? [];

        $chartLabels = collect($hourly)->map(fn($h) => substr($h['hour'], 11, 5))->values();
        $chartVolume = collect($hourly)->map(fn($h) => round($h['volume'], 2))->values();

        return view('nestworld-analytics::admin.index', compact(
            'hours', 'totalVolume', 'totalTransactions',
            'anomalies', 'topSellers', 'topItems',
            'chartLabels', 'chartVolume'
        ));
    }

    public function resolveAnomaly(int $id)
    {
        $this->api()->post("/analytics/anomalies/{$id}/resolve");

        // Скидаємо кеш аномалій щоб наступний запит підтягнув свіжі дані
        DB::table('nestworld_analytics_cache')->where('key', 'anomalies')->delete();

        return back()->with('success', trans('nestworld-analytics::messages.anomaly_resolved'));
    }
}
