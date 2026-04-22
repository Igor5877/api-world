<?php

namespace Azuriom\Plugin\NestworldAnalytics\Providers;

use Azuriom\Extensions\Plugin\BasePluginServiceProvider;

class AnalyticsServiceProvider extends BasePluginServiceProvider
{
    public function register(): void
    {
        $this->registerMiddlewares();

        $this->mergeConfigFrom(
            plugin_path('nestworld-analytics', 'config/nestworld-analytics.php'),
            'nestworld-analytics'
        );
    }

    public function boot(): void
    {
        $this->loadViews();
        $this->loadTranslations();
        $this->loadMigrations();
        $this->registerAdminNavigation();
    }

    protected function adminNavigation(): array
    {
        return [
            'nestworld-analytics' => [
                'name'  => trans('nestworld-analytics::messages.nav_title'),
                'icon'  => 'bi bi-bar-chart-line',
                'route' => 'nestworld-analytics.admin.index',
            ],
        ];
    }
}
