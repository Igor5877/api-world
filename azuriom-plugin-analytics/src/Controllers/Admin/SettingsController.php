<?php

namespace Azuriom\Plugin\NestworldAnalytics\Controllers\Admin;

use Azuriom\Http\Controllers\Controller;
use Azuriom\Models\Setting;
use Illuminate\Http\Request;

class SettingsController extends Controller
{
    public function index()
    {
        return view('nestworld-analytics::admin.settings', [
            'apiUrl'     => setting('nestworld_api_url', 'http://127.0.0.1:8000/api/v1'),
            'apiTimeout' => setting('nestworld_api_timeout', 5),
        ]);
    }

    public function update(Request $request)
    {
        $request->validate([
            'api_url'     => ['required', 'url'],
            'api_timeout' => ['required', 'integer', 'min:1', 'max:30'],
        ]);

        Setting::updateSettings([
            'nestworld_api_url'     => rtrim($request->api_url, '/'),
            'nestworld_api_timeout' => (int) $request->api_timeout,
        ]);

        return back()->with('success', trans('nestworld-analytics::messages.settings_saved'));
    }
}
