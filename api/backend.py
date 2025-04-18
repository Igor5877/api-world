from fastapi import FastAPI
from pydantic import BaseModel
import subprocess
import time
import json

app = FastAPI()

class BazaRequest(BaseModel):
    player: str
    name: str

@app.post("/baza/add")
def create_or_start_container(data: BazaRequest):
    container = f"{data.player}-{data.name}"

    # Перевірка чи існує контейнер
    result = subprocess.run(["lxc", "list", container, "--format", "json"], capture_output=True, text=True)
    containers = json.loads(result.stdout)
    
    if not containers:
        # Якщо не існує → копіюємо з базового шаблону
        copy_result = subprocess.run(["lxc", "copy", "wan-blok", container], capture_output=True, text=True)
        if copy_result.returncode != 0:
            return {"status": "error", "message": "Помилка при копіюванні контейнера"}

    # Стартуємо контейнер
    subprocess.run(["lxc", "start", container])

    # Чекаємо 3–5 секунд щоб контейнер встиг піднятись
    time.sleep(5)

    # Отримуємо IP
    ip_result = subprocess.run(["lxc", "list", container, "--format", "json"], capture_output=True, text=True)
    try:
        info = json.loads(ip_result.stdout)[0]
        ip = info["state"]["network"]["eth0"]["addresses"]
        ipv4 = next((addr["address"] for addr in ip if addr["family"] == "inet"), None)
    except Exception as e:
        return {"status": "error", "message": f"IP не знайдено: {e}"}

    # Повертаємо IP + порт (припустимо, постійно 25565 — бо всередині вже є проксі)
    return {
        "status": "success",
        "container": container,
        "ip": ipv4,
        "port": 25565
    }

