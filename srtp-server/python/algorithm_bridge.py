#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
SRTP Algorithm Bridge - Python side
Reads JSON from stdin, runs TSP algorithm, outputs JSON to stdout.
Called by Java Spring Boot backend via subprocess.
"""

import json
import math
import sys
import time
import os
import importlib.util

# Add srtp-app-source to path (sibling to srtp-server)
_BRIDGE_DIR = os.path.dirname(os.path.abspath(__file__))        # srtp-server/python
_SRTP_DIR = os.path.dirname(os.path.dirname(_BRIDGE_DIR))       # srtp
_APP_SOURCE_DIR = os.path.join(_SRTP_DIR, 'srtp-app-source')
sys.path.insert(0, _APP_SOURCE_DIR)

# 算法核心模块需要导出的全部接口
_REQUIRED_API = (
    'format_path',
    'run_sa', 'run_ts', 'run_ga', 'run_aca',
    'run_pso', 'run_som', 'run_dp', 'run_bnb',
    'City',
)


def _load_algorithm_core():
    """加载算法核心模块（srtp-app-source/四算法整合优化.py）。

    正常情况下按固定模块名导入。但源文件名含中文，在跨平台打包与传输
    （CI 产物 / tar / scp 等）过程中可能被工具链改写，因此这里增加兜底：
    直接按【文件路径】扫描加载 srtp-app-source 下第一个导出了全部所需接口的
    .py 模块。按路径加载不依赖文件名，可彻底免疫文件名被改写的问题。
    """
    try:
        module = importlib.import_module('四算法整合优化')
        if all(hasattr(module, name) for name in _REQUIRED_API):
            return module
    except ImportError:
        pass

    if os.path.isdir(_APP_SOURCE_DIR):
        for index, filename in enumerate(sorted(os.listdir(_APP_SOURCE_DIR))):
            if not filename.endswith('.py') or filename.startswith('_'):
                continue
            module_path = os.path.join(_APP_SOURCE_DIR, filename)
            try:
                spec = importlib.util.spec_from_file_location(
                    '_srtp_core_%d' % index, module_path)
                if spec is None or spec.loader is None:
                    continue
                candidate = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(candidate)
            except Exception:
                continue
            if all(hasattr(candidate, name) for name in _REQUIRED_API):
                return candidate

    raise ImportError(
        '未能在 %s 中找到算法核心模块（需导出: %s）'
        % (_APP_SOURCE_DIR, ', '.join(_REQUIRED_API))
    )


_CORE = _load_algorithm_core()

format_path = _CORE.format_path
run_sa = _CORE.run_sa
run_ts = _CORE.run_ts
run_ga = _CORE.run_ga
run_aca = _CORE.run_aca
run_pso = _CORE.run_pso
run_som = _CORE.run_som
run_dp = _CORE.run_dp
run_bnb = _CORE.run_bnb
City = _CORE.City


ALGO_MAP = {
    "sa":  run_sa,
    "ts":  run_ts,
    "ga":  run_ga,
    "aca": run_aca,
    "pso": run_pso,
    "som": run_som,
    "dp":  run_dp,
    "bnb": run_bnb,
}

ALGO_LABELS = {
    "sa":  "SA 模拟退火",
    "ts":  "TS 禁忌搜索",
    "ga":  "GA 遗传算法",
    "aca": "ACA 蚁群算法",
    "pso": "PSO 粒子群",
    "som": "SOM 自组织映射",
    "dp":  "DP 动态规划",
    "bnb": "BnB 分支限界",
}


def _format_complexity(score):
    """Format a complexity score into a human-readable label."""
    if score <= 0:
        return "N/A"
    if score < 1_000:
        return f"{int(score)} op"
    if score < 1_000_000:
        return f"{score / 1_000:.1f}K op"
    if score < 1_000_000_000:
        return f"{score / 1_000_000:.1f}M op"
    return f"{score / 1_000_000_000:.1f}G op"


def _compute_complexity(algo_id, params, city_count, iter_key=None, pop_key=None):
    """统一算力估算: iterations × population × n²

    跨算法可比的简化模型。各算法对 iterations/population 的映射：
      PSO: iter=iterations,        pop=particle_count
      SA:  iter=outer×inner,       pop=1
      GA:  iter=generations,       pop=population_size
      ACA: iter=iterations,        pop=ant_count
      TS:  iter=iterations,        pop=neighbor_count
      SOM: iter=iterations,        pop=3n (neuron_count)
      DP:  iter=n,                 pop=1
      BnB: iter=branch^depth,      pop=1
      自定义: iter=params[iter_key], pop=params[pop_key]
    """
    n = max(1, city_count)
    n_sq = n * n

    if algo_id == "pso":
        iterations = int(params.get("iterations", 0))
        population = int(params.get("particle_count", 0))

    elif algo_id == "sa":
        initial_temp = float(params.get("initial_temp", 1000.0))
        final_temp = float(params.get("final_temp", 0.1))
        cooling_rate = float(params.get("cooling_rate", 0.95))
        inner = int(params.get("inner_iterations", 200))
        if cooling_rate >= 1.0 or cooling_rate <= 0.0:
            outer_steps = max(1, inner)
        else:
            outer_steps = max(1, int(
                math.log(final_temp / max(initial_temp, 1e-10)) / math.log(cooling_rate)
            ))
        iterations = outer_steps * inner
        population = 1

    elif algo_id == "ga":
        iterations = int(params.get("generations", 0))
        population = int(params.get("population_size", 0))

    elif algo_id == "aca":
        iterations = int(params.get("iterations", 0))
        population = int(params.get("ant_count", 0))

    elif algo_id == "ts":
        iterations = int(params.get("iterations", 0))
        population = int(params.get("neighbor_count", 0))

    elif algo_id == "som":
        iterations = int(params.get("iterations", 0))
        population = 3 * n  # neuron_count = 3n

    elif algo_id == "dp":
        # Real Held-Karp: O(n² · 2^n). Above DP_MAX_CITIES (18), falls back
        # to the Cheapest Insertion heuristic (O(n³)).
        if n <= 18:
            iterations = 2 ** n  # Held-Karp state count
            population = 1
        else:
            iterations = n  # Cheapest Insertion fallback
            population = 1

    elif algo_id == "bnb":
        if n <= 10:
            branch_factor = n * 0.55
            depth = int(n * 0.9)
        elif n <= 12:
            branch_factor = n * 0.40
            depth = int(n * 0.75)
        else:
            branch_factor = n * 0.25
            depth = int(n * 0.6)
        bf = max(1.0, branch_factor)
        nodes = 0
        for d in range(depth + 1):
            nodes += int(bf ** d)
        iterations = min(nodes, 10_000_000)
        population = 1

    else:
        # 自定义算法: 从用户标记的控制变量取 iter/pop
        iterations = int(params.get(iter_key, 1)) if iter_key else 1
        population = int(params.get(pop_key, 1)) if pop_key else 1

    iterations = max(1, iterations)
    population = max(1, population)
    return iterations * population * n_sq


def main():
    try:
        raw = sys.stdin.read()
        inp = json.loads(raw)
    except Exception as e:
        print(json.dumps({"error": f"Invalid input JSON: {e}"}))
        sys.exit(1)

    algo_id = inp.get("algorithm_id", "")
    cities_data = inp.get("cities", [])
    params = inp.get("params", {})
    seed = inp.get("seed")
    weights = inp.get("weights")
    custom_code = inp.get("custom_code")
    custom_iter_key = inp.get("custom_iter_key")
    custom_pop_key = inp.get("custom_pop_key")

    # Build City objects
    cities = []
    for c in cities_data:
        city = City(name=str(c.get("name", "")), x=float(c.get("x", 0)), y=float(c.get("y", 0)))
        cities.append(city)

    if len(cities) < 2:
        print(json.dumps({"error": "At least 2 cities required"}))
        sys.exit(1)

    city_count = len(cities)

    # Handle weights: convert from simple dict to expected format {(i,j): value}
    weight_dict = None
    if weights:
        weight_dict = {}
        for key, val in weights.items():
            # Handle both "0->1" and "0-1" and "0,1" formats
            if '->' in key:
                parts = key.split('->')
            else:
                parts = key.replace("-", ",").split(",")
            if len(parts) == 2:
                weight_dict[(int(parts[0]), int(parts[1]))] = float(val)

    if seed is not None:
        seed = int(seed)

    # Determine run function: built-in or custom
    is_custom = algo_id not in ALGO_MAP and bool(custom_code)
    if is_custom:
        # Write custom code to a temporary module file and load it
        safe_id = "".join(ch if ch.isalnum() else "_" for ch in str(algo_id))
        custom_module_path = os.path.join(_BRIDGE_DIR, f"_custom_{safe_id}.py")
        try:
            with open(custom_module_path, "w", encoding="utf-8") as f:
                f.write(custom_code)
        except Exception as e:
            print(json.dumps({"error": f"无法写入自定义算法代码: {e}"}))
            sys.exit(1)

        spec = importlib.util.spec_from_file_location(f"_custom_{safe_id}", custom_module_path)
        if spec is None or spec.loader is None:
            print(json.dumps({"error": "无法加载自定义算法模块"}))
            sys.exit(1)
        try:
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
        except SyntaxError as e:
            print(json.dumps({
                "algorithm_id": algo_id,
                "algorithm_label": inp.get("algorithm_label", algo_id),
                "error": f"自定义算法语法错误 (行{e.lineno}): {e.msg}",
            }, ensure_ascii=False))
            sys.exit(0)
        except Exception as e:
            print(json.dumps({
                "algorithm_id": algo_id,
                "algorithm_label": inp.get("algorithm_label", algo_id),
                "error": f"自定义算法加载失败: {e}",
            }, ensure_ascii=False))
            sys.exit(0)

        if not hasattr(module, "run") or not callable(module.run):
            print(json.dumps({
                "algorithm_id": algo_id,
                "algorithm_label": inp.get("algorithm_label", algo_id),
                "error": "自定义算法代码必须定义 def run(cities, params, seed, weights) 函数",
            }, ensure_ascii=False))
            sys.exit(0)
        run_func = module.run
        algo_label = inp.get("algorithm_label", algo_id)
    else:
        if algo_id not in ALGO_MAP:
            print(json.dumps({"error": f"Unknown algorithm: {algo_id}"}))
            sys.exit(1)
        run_func = ALGO_MAP[algo_id]
        algo_label = ALGO_LABELS.get(algo_id, algo_id)

    # Run algorithm
    t0 = time.perf_counter()
    try:
        best_distance, best_path = run_func(cities, params=params, seed=seed, weights=weight_dict)
    except Exception as e:
        print(json.dumps({
            "algorithm_id": algo_id,
            "algorithm_label": algo_label,
            "error": f"算法执行失败: {e}",
            "detail": str(e)
        }, ensure_ascii=False))
        sys.exit(0)
    elapsed_ms = int((time.perf_counter() - t0) * 1000)

    # Compute complexity (unified formula)
    if is_custom:
        complexity_score = _compute_complexity(
            algo_id, params, city_count,
            iter_key=custom_iter_key, pop_key=custom_pop_key
        )
    else:
        complexity_score = _compute_complexity(algo_id, params, city_count)
    complexity_label = _format_complexity(complexity_score)

    # Build result
    result = {
        "algorithm_id": algo_id,
        "algorithm_label": algo_label,
        "best_distance": float(best_distance),
        "best_path": list(best_path) if best_path else [],
        "best_path_text": format_path(best_path) if best_path else "",
        "elapsed_ms": elapsed_ms,
        "complexity_score": complexity_score,
        "complexity_label": complexity_label,
    }

    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
