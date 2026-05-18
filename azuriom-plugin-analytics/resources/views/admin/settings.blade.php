@extends('admin.layouts.admin')

@section('title', trans('nestworld-analytics::messages.settings_title'))

@section('content')
<div class="container-fluid">
    <div class="row justify-content-center">
        <div class="col-md-6">

            @if(session('success'))
                <div class="alert alert-success">{{ session('success') }}</div>
            @endif

            <div class="card">
                <div class="card-header fw-semibold">
                    {{ trans('nestworld-analytics::messages.settings_title') }}
                </div>
                <div class="card-body">
                    <form method="POST" action="{{ route('nestworld-analytics.admin.settings.update') }}">
                        @csrf

                        <div class="mb-3">
                            <label class="form-label">{{ trans('nestworld-analytics::messages.api_url') }}</label>
                            <input type="url" name="api_url" class="form-control @error('api_url') is-invalid @enderror"
                                   value="{{ old('api_url', $apiUrl) }}"
                                   placeholder="http://10.0.0.5:8000/api/v1">
                            @error('api_url')
                                <div class="invalid-feedback">{{ $message }}</div>
                            @enderror
                            <div class="form-text">{{ trans('nestworld-analytics::messages.api_url_hint') }}</div>
                        </div>

                        <div class="mb-3">
                            <label class="form-label">{{ trans('nestworld-analytics::messages.api_timeout') }}</label>
                            <input type="number" name="api_timeout" class="form-control"
                                   value="{{ old('api_timeout', $apiTimeout) }}" min="1" max="30">
                        </div>

                        <button type="submit" class="btn btn-primary">
                            {{ trans('nestworld-analytics::messages.save') }}
                        </button>
                        <a href="{{ route('nestworld-analytics.admin.index') }}" class="btn btn-outline-secondary ms-2">
                            {{ trans('nestworld-analytics::messages.back') }}
                        </a>
                    </form>
                </div>
            </div>

        </div>
    </div>
</div>
@endsection
