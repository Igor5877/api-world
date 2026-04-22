<?php

use Illuminate\Database\Migrations\Migration;
use Illuminate\Database\Schema\Blueprint;
use Illuminate\Support\Facades\Schema;

return new class extends Migration
{
    public function up(): void
    {
        Schema::create('nestworld_analytics_cache', function (Blueprint $table) {
            $table->string('key', 100)->primary();
            $table->json('data');
            $table->timestamp('cached_at');
        });
    }

    public function down(): void
    {
        Schema::dropIfExists('nestworld_analytics_cache');
    }
};
