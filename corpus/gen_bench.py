#!/usr/bin/env python3
"""Генератор bench-входов corpus/<пример>/inputs/bench-<n>.txt (G4): детерминированно, растущий размер n, 2n, 4n.

Запуск из корня репозитория:  python3 corpus/gen_bench.py
Входы подобраны так, чтобы нагружать заложенные дефекты (O(n²)-циклы, поиск в списке, конкатенацию),
и бенчмарк в /verify показывал разницу «до/после» исправления.
"""
import os
import random

ROOT = os.path.dirname(os.path.abspath(__file__))
SIZES = [2000, 4000, 8000]


def write(example, n, lines):
    path = os.path.join(ROOT, example, "inputs", f"bench-{n}.txt")
    if not os.path.isdir(os.path.dirname(path)):
        return
    with open(path, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")


def shop_orders(n):
    rnd = random.Random(n)
    cities = ["Ростов", "Москва", "Казань", "Самара", "Сочи"]
    cats = ["книги", "дом", "электроника", "спорт"]
    lines = []
    customers = max(5, n // 10)
    for i in range(customers):
        vip = " VIP" if i % 7 == 0 else ""
        lines.append(f"CUSTOMER c{i} Клиент{i} {rnd.choice(cities)}{vip}")
    for i in range(40):
        lines.append(f"PRODUCT p{i} {rnd.choice(cats)} {rnd.randint(100, 20000)}.{rnd.randint(0, 99):02d} 1000000 Товар номер {i}")
    lines.append("PROMO BENCH 7")
    for i in range(n):
        items = ",".join(f"p{rnd.randrange(40)}:{rnd.randint(1, 3)}" for _ in range(rnd.randint(1, 3)))
        lines.append(f"ORDER o{i} c{rnd.randrange(customers)} {items}")
        if i % 25 == 24:
            lines.append(f"APPLY o{rnd.randrange(i)} BENCH")
        if i % 40 == 39:
            lines.append(f"CANCEL o{i - 1}")
    lines.append("REPORT")
    return lines


def library(size):
    n = size // 4
    rnd = random.Random(n)
    authors = [f"Автор{i}" for i in range(n // 20 + 3)]
    words = ["тайна", "сад", "город", "море", "звезда", "код", "путь", "дом", "ночь", "свет"]
    lines = []
    books = n // 2
    for i in range(books):
        title = " ".join(rnd.sample(words, 2)) + f" {i}"
        lines.append(f"BOOK b{i} {rnd.randint(1850, 2024)} {rnd.choice(authors)} | {title}")
        lines.append(f"COPY b{i} {rnd.randint(1, 3)}")
    for i in range(n // 10):
        lines.append(f"READER r{i} {rnd.choice(['STUDENT', 'ADULT', 'SENIOR'])} Читатель{i}")
    day = 1
    for i in range(n):
        day += rnd.randint(0, 1)
        r = rnd.randrange(n // 10)
        lines.append(f"TAKE {day} r{r} b{rnd.randrange(books)}")
        if i % 3 == 0:
            lines.append(f"SEARCH {rnd.choice(words)}")
    lines.append("STATS")
    return lines


def bank(size):
    rnd = random.Random(size)
    n = min(64, 8 + size // 200)
    owners = ["Иванов", "Петров", "Сидорова", "Smith", "Ким"]
    lines = []
    for i in range(n):
        lines.append(f"OPEN a{i} {rnd.choice(['RUB', 'RUB', 'USD', 'EUR'])} {rnd.choice(owners)}")
        lines.append(f"DEPOSIT a{i} {rnd.randint(1000, 900000)}")
    for i in range(size):
        a, b = rnd.randrange(n), rnd.randrange(n)
        op = rnd.random()
        if op < 0.5 and a != b:
            lines.append(f"TRANSFER a{a} a{b} {rnd.randint(1, 500)}.{rnd.randint(0, 99):02d}")
        elif op < 0.8:
            lines.append(f"DEPOSIT a{a} {rnd.randint(1, 5000)}")
        else:
            lines.append(f"WITHDRAW a{a} {rnd.randint(1, 3000)}")
        if i % 200 == 199:
            lines.append(f"STATEMENT a{a}")
    lines.append("INTEREST 7.5 12")
    lines.append("HISTOGRAM")
    return lines


def text(size):
    rnd = random.Random(size)
    syll = ["ка", "ро", "ли", "мо", "на", "ту", "ре", "со", "ве", "да", "ми", "ло"]
    vocab = ["".join(rnd.choice(syll) for _ in range(rnd.randint(2, 5))) for _ in range(size + 50)]
    lines = []
    for i in range(size // 3):
        words = [rnd.choice(vocab) for _ in range(rnd.randint(6, 14))]
        words[0] = words[0].capitalize()
        lines.append(" ".join(words) + rnd.choice([".", "!", "?", ", и снова.", "..."]))
    return lines


def course(size):
    rnd = random.Random(size)
    groups = ["ИВТ-21", "ИВТ-22", "ПИ-21", "ПИ-22", "МО-23"]
    n = size // 20
    lines = []
    for i in range(n):
        lines.append(f"STUDENT s{i} {rnd.choice(groups)} Студент{i}")
    tasks = [("QUIZ", 10), ("QUIZ", 10), ("LAB", 20), ("LAB", 20), ("LAB", 20), ("PROJECT", 50), ("EXAM", 100)]
    for k, (t, mx) in enumerate(tasks):
        lines.append(f"TASK t{k} {t} {mx} {10 + 5 * k} Задание {k}")
    for i in range(n):
        for k, (t, mx) in enumerate(tasks):
            if rnd.random() < 0.9:
                lines.append(f"SUBMIT s{i} t{k} {rnd.randint(0, mx)} {10 + 5 * k + rnd.randint(-3, 2)}")
        if rnd.random() < 0.2:
            lines.append(f"BONUS s{i} {rnd.randint(1, 5)} активность")
    lines.append("REPORT")
    return lines


def warehouse(size):
    rnd = random.Random(size)
    zones = ["A1", "A2", "B1", "B2", "C"]
    lines = []
    for i in range(size):
        lines.append(f"ITEM S-{i:06d} {rnd.choice(zones)} {rnd.randint(1, 50000) / 100} Товар {i}")
    for i in range(size):
        lines.append(f"MOVE S-{rnd.randrange(size):06d} {rnd.randint(1, 100)}")
    for i in range(size):
        sku = f"S-{rnd.randrange(size):06d}" if rnd.random() < 0.95 else f"X-{rnd.randrange(999):03d}"
        lines.append(f"SCAN {sku}")
    lines.append("AUDIT")
    return lines


GENERATORS = {
    "01-shop-orders": shop_orders,
    "02-library": library,
    "03-bank": bank,
    "04-text-stats": text,
    "05-course-grades": course,
    "06-demo-warehouse": warehouse,
}

# Свои размеры: у демо-примера горячие участки квадратичные, разница «до/после» видна на больших n.
SIZES_BY_EXAMPLE = {
    "06-demo-warehouse": [5000, 10000, 20000],
}

if __name__ == "__main__":
    for example, gen in GENERATORS.items():
        for n in SIZES_BY_EXAMPLE.get(example, SIZES):
            write(example, n, gen(n))
            print(f"{example}/inputs/bench-{n}.txt")
