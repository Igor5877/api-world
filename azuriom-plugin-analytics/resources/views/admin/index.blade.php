@extends('admin.layouts.admin')

@section('title', trans('nestworld-analytics::messages.page_title'))

@section('content')
<div class="container-fluid">

    {{-- Фільтр --}}
    <div class="mb-3 d-flex align-items-center gap-2">
        <span class="text-muted">{{ trans('nestworld-analytics::messages.period') }}:</span>
        @foreach([6, 24, 48, 168] as $h)
            <a href="?hours={{ $h }}"
               class="btn btn-sm {{ $hours === $h ? 'btn-primary' : 'btn-outline-secondary' }}">
                {{ $h }}{{ trans('nestworld-analytics::messages.hours') }}
            </a>
        @endforeach
    </div>

    @if(session('success'))
        <div class="alert alert-success">{{ session('success') }}</div>
    @endif

    {{-- Картки --}}
    <div class="row g-3 mb-4">
        <div class="col-md-4">
            <div class="card text-center h-100">
                <div class="card-body">
                    <div class="fs-2 fw-bold text-primary">{{ number_format($totalVolume, 2) }}</div>
                    <div class="text-muted">{{ trans('nestworld-analytics::messages.total_volume') }}</div>
                </div>
            </div>
        </div>
        <div class="col-md-4">
            <div class="card text-center h-100">
                <div class="card-body">
                    <div class="fs-2 fw-bold text-success">{{ $totalTransactions }}</div>
                    <div class="text-muted">{{ trans('nestworld-analytics::messages.total_tx') }}</div>
                </div>
            </div>
        </div>
        <div class="col-md-4">
            <div class="card text-center h-100">
                <div class="card-body">
                    <div class="fs-2 fw-bold {{ count($anomalies) > 0 ? 'text-danger' : 'text-success' }}">
                        {{ count($anomalies) }}
                    </div>
                    <div class="text-muted">{{ trans('nestworld-analytics::messages.anomalies') }} ({{ trans('nestworld-analytics::messages.unresolved') }})</div>
                </div>
            </div>
        </div>
    </div>

    {{-- Графік --}}
    <div class="card mb-4">
        <div class="card-header">{{ trans('nestworld-analytics::messages.hourly_chart') }}</div>
        <div class="card-body">
            <canvas id="volumeChart" height="80"></canvas>
        </div>
    </div>

    <div class="row g-3">

        {{-- Аномалії --}}
        <div class="col-lg-6">
            <div class="card h-100">
                <div class="card-header text-danger fw-semibold">
                    {{ trans('nestworld-analytics::messages.anomalies') }}
                </div>
                <div class="card-body p-0">
                    @if(empty($anomalies))
                        <p class="p-3 text-muted mb-0">{{ trans('nestworld-analytics::messages.no_anomalies') }}</p>
                    @else
                        <div class="table-responsive">
                            <table class="table table-sm table-hover mb-0">
                                <thead class="table-light">
                                    <tr>
                                        <th>{{ trans('nestworld-analytics::messages.item') }}</th>
                                        <th>{{ trans('nestworld-analytics::messages.multiplier') }}</th>
                                        <th>{{ trans('nestworld-analytics::messages.actual') }}</th>
                                        <th>{{ trans('nestworld-analytics::messages.detected') }}</th>
                                        <th></th>
                                    </tr>
                                </thead>
                                <tbody>
                                    @foreach($anomalies as $anomaly)
                                        <tr>
                                            <td class="text-break" style="max-width:180px">
                                                <code>{{ $anomaly['item_id'] }}</code>
                                            </td>
                                            <td>
                                                <span class="badge bg-danger">{{ $anomaly['multiplier'] }}x</span>
                                            </td>
                                            <td>{{ number_format($anomaly['actual_volume'], 2) }}</td>
                                            <td class="text-nowrap">
                                                {{ substr($anomaly['detected_at'] ?? '', 0, 16) }}
                                            </td>
                                            <td>
                                                <form method="POST"
                                                      action="{{ route('nestworld-analytics.admin.anomalies.resolve', $anomaly['id']) }}">
                                                    @csrf
                                                    <button type="submit" class="btn btn-sm btn-outline-success">
                                                        {{ trans('nestworld-analytics::messages.resolve') }}
                                                    </button>
                                                </form>
                                            </td>
                                        </tr>
                                    @endforeach
                                </tbody>
                            </table>
                        </div>
                    @endif
                </div>
            </div>
        </div>

        {{-- Топ продавці --}}
        <div class="col-lg-6">
            <div class="card h-100">
                <div class="card-header fw-semibold">{{ trans('nestworld-analytics::messages.top_sellers') }}</div>
                <div class="card-body p-0">
                    <div class="table-responsive">
                        <table class="table table-sm table-hover mb-0">
                            <thead class="table-light">
                                <tr>
                                    <th>#</th>
                                    <th>{{ trans('nestworld-analytics::messages.seller_id') }}</th>
                                    <th>{{ trans('nestworld-analytics::messages.earned') }}</th>
                                    <th>{{ trans('nestworld-analytics::messages.items_sold') }}</th>
                                </tr>
                            </thead>
                            <tbody>
                                @forelse($topSellers as $i => $seller)
                                    <tr>
                                        <td class="text-muted">{{ $i + 1 }}</td>
                                        <td>{{ $seller['seller_azuriom_id'] }}</td>
                                        <td>{{ number_format($seller['total_earned'], 2) }}</td>
                                        <td>{{ $seller['total_items'] }}</td>
                                    </tr>
                                @empty
                                    <tr><td colspan="4" class="text-muted p-3">—</td></tr>
                                @endforelse
                            </tbody>
                        </table>
                    </div>
                </div>
            </div>
        </div>

    </div>

    {{-- Топ предмети --}}
    <div class="card mt-3">
        <div class="card-header fw-semibold">{{ trans('nestworld-analytics::messages.top_items') }}</div>
        <div class="card-body p-0">
            <div class="table-responsive">
                <table class="table table-sm table-hover mb-0">
                    <thead class="table-light">
                        <tr>
                            <th>#</th>
                            <th>{{ trans('nestworld-analytics::messages.item') }}</th>
                            <th>{{ trans('nestworld-analytics::messages.volume') }}</th>
                            <th>{{ trans('nestworld-analytics::messages.items_sold') }}</th>
                            <th>{{ trans('nestworld-analytics::messages.transactions') }}</th>
                        </tr>
                    </thead>
                    <tbody>
                        @forelse($topItems as $i => $item)
                            <tr>
                                <td class="text-muted">{{ $i + 1 }}</td>
                                <td><code>{{ $item['item_id'] }}</code></td>
                                <td>{{ number_format($item['total_volume'], 2) }}</td>
                                <td>{{ $item['total_sold'] }}</td>
                                <td>{{ $item['transaction_count'] }}</td>
                            </tr>
                        @empty
                            <tr><td colspan="5" class="text-muted p-3">—</td></tr>
                        @endforelse
                    </tbody>
                </table>
            </div>
        </div>
    </div>

</div>
@endsection

@push('footer-scripts')
<script src="https://cdn.jsdelivr.net/npm/chart.js@4/dist/chart.umd.min.js"></script>
<script>
new Chart(document.getElementById('volumeChart'), {
    type: 'bar',
    data: {
        labels: @json($chartLabels),
        datasets: [{
            label: '{{ trans('nestworld-analytics::messages.volume') }}',
            data: @json($chartVolume),
            backgroundColor: 'rgba(13, 110, 253, 0.6)',
            borderColor: 'rgba(13, 110, 253, 1)',
            borderWidth: 1,
        }]
    },
    options: {
        responsive: true,
        plugins: { legend: { display: false } },
        scales: { y: { beginAtZero: true } }
    }
});
</script>
@endpush
