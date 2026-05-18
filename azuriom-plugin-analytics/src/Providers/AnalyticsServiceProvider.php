<?php

namespace Azuriom\Plugin\NestworldAnalytics\Providers;

use Azuriom\Extensions\Plugin\BasePluginServiceProvider;

class AnalyticsServiceProvider extends BasePluginServiceProvider
{
    public function register(): void
    {
        $this->registerMiddlewares();
    }

    public function boot(): void
    {
        $this->loadViews();
        $this->loadTranslations();
        $this->loadMigrations();
        $this->registerAdminNavigation();

        $this->router->middleware(['web', 'auth', 'can:admin'])
            ->prefix('admin/nestworld-analytics')
            ->name('nestworld-analytics.admin.')
            ->group($this->pluginPath('routes/admin.php'));
    }

    protected function adminNavigation(): array
    {
        return [
            'nestworld-analytics' => [
                'name'  => trans('nestworld-analytics::messages.nav_title'),
                'type'  => 'dropdown',
                'icon'  => 'bi bi-bar-chart-line',
                'route' => 'nestworld-analytics.admin.*',
                'items' => [
                    'nestworld-analytics.admin.index'    => ['name' => trans('nestworld-analytics::messages.nav_title')],
                    'nestworld-analytics.admin.settings' => ['name' => trans('nestworld-analytics::messages.settings_title')],
                ],
            ],
        ];
    }
}
