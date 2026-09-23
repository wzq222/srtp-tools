#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
SRTP Desktop - TSP Algorithm Comparison & Optimization Platform
四算法整合优化 - Core TSP algorithm implementations
Recovered from Python 3.13 bytecode
"""

import heapq
import math
import random
from dataclasses import dataclass


# ============================================================
# Data structures
# ============================================================

@dataclass
class City:
    """Represents a city with coordinates."""
    name: str = ""
    x: float = 0.0
    y: float = 0.0


BNB_MAX_CITIES = 15
# Held-Karp DP is O(n² · 2^n); above this threshold it becomes too slow/memory-heavy
# in pure Python, so we fall back to the Cheapest Insertion heuristic.
DP_MAX_CITIES = 18


# ============================================================
# Core distance functions
# ============================================================

def edge_distance(cities, start_idx, end_idx, weights=None):
    """Calculate distance between two cities by their indices.

    Args:
        cities: List of City objects
        start_idx: Index of start city
        end_idx: Index of end city
        weights: Optional dict mapping (start_idx, end_idx) tuples to pre-set distances

    Returns:
        float: Distance between the two cities
    """
    if weights is not None:
        weight = weights.get((start_idx, end_idx))
        if weight is None:
            raise ValueError("缺少边权重")
        return float(weight)

    a = cities[start_idx]
    b = cities[end_idx]
    return math.hypot(b.x - a.x, b.y - a.y)


def calculate_distance(cities, path, weights=None):
    """Calculate total distance of a tour path.

    Args:
        cities: List of City objects
        path: List of city indices forming the tour
        weights: Optional dict of pre-set edge weights

    Returns:
        float: Total tour distance
    """
    total = 0.0
    n = len(path)
    for i in range(n):
        a_idx = path[i]
        b_idx = path[(i + 1) % n]
        total += edge_distance(cities, a_idx, b_idx, weights)
    return total


# ============================================================
# Path manipulation helpers
# ============================================================

def perturb_path(path, rng=None):
    """Randomly perturb a path using various operators.

    Supports: swap, reverse segment, insert, 3-opt-like moves.
    """
    if rng is None:
        rng = random

    new_path = path.copy()
    op = rng.choice(["swap", "reverse", "insert", "3opt"])
    n = len(path)

    if op == "swap":
        i, j = rng.sample(range(n), 2)
        new_path[i], new_path[j] = new_path[j], new_path[i]
    elif op == "reverse":
        i, j = sorted(rng.sample(range(n), 2))
        new_path[i:j+1] = reversed(new_path[i:j+1])
    elif op == "insert":
        i = rng.randint(0, n - 1)
        node = new_path.pop(i)
        j = rng.randint(0, n - 2)
        new_path.insert(j, node)
    elif op == "3opt":
        i, j, k = sorted(rng.sample(range(n), 3))
        new_path = new_path[:i] + new_path[j:k] + new_path[i:j] + new_path[k:]

    return new_path


def guided_move_towards(path, target_path, rng=None):
    """Move the current path towards a target path (for PSO).

    Preserves some structure from both paths.
    """
    if rng is None:
        rng = random

    n = len(path)
    child = path.copy()

    # Take a random segment from target
    start, end = sorted(rng.sample(range(n), 2))
    insert_values = target_path[start:end+1]

    # Remove those values from current path
    for x in insert_values:
        try:
            child.remove(x)
        except ValueError:
            pass

    # Insert at random position
    insert_idx = rng.randint(0, len(child))
    for i, val in enumerate(insert_values):
        child.insert(insert_idx + i, val)

    return child


def two_opt_local_search(cities, path, rng=None, max_trials=6, weights=None):
    """2-opt local search improvement.

    Args:
        cities: List of City objects
        path: Initial path
        rng: Random number generator
        max_trials: Maximum number of improvement trials
        weights: Optional edge weights

    Returns:
        tuple: (improved_path, score)
    """
    if rng is None:
        rng = random

    best_path = path.copy()
    best_score = calculate_distance(cities, best_path, weights)
    n = len(path)

    for _ in range(max_trials):
        i, j = sorted(rng.sample(range(n), 2))
        if j - i >= n - 1:
            continue
        candidate = best_path.copy()
        candidate[i:j+1] = reversed(candidate[i:j+1])
        score = calculate_distance(cities, candidate, weights)
        if score < best_score:
            best_path, best_score = candidate, score

    return best_path, best_score


def two_opt_full(cities, path, weights=None, max_passes=20):
    """Full (exhaustive) 2-opt local search.

    Tries ALL (i, j) segment-reversal moves each pass, repeating until no
    improvement is found or max_passes is reached. Supports asymmetric
    (directed) weights — the entire path is re-evaluated after each reversal,
    so reversed-segment edges are correctly priced via edge_distance.

    Unlike two_opt_local_search (which samples randomly), this performs a
    complete neighborhood scan and is guaranteed to reach a 2-opt local
    optimum. Used by SOM where the initial path may be far from optimal.
    """
    best_path = path.copy()
    best_score = calculate_distance(cities, best_path, weights)
    n = len(best_path)
    improved = True
    passes = 0
    while improved and passes < max_passes:
        improved = False
        for i in range(n - 1):
            for j in range(i + 1, n):
                if j - i >= n - 1:
                    continue
                candidate = best_path.copy()
                candidate[i:j+1] = reversed(candidate[i:j+1])
                score = calculate_distance(cities, candidate, weights)
                if score < best_score:
                    best_path, best_score = candidate, score
                    improved = True
        passes += 1
    return best_path, best_score


# ============================================================
# PSO - Particle Swarm Optimization
# ============================================================

def run_pso(cities, params=None, seed=None, weights=None):
    """Particle Swarm Optimization for TSP.

    Parameters:
        iterations: Number of iterations (default: 1500)
        particle_count: Number of particles (default: 80)
        inertia_weight: Inertia weight ω (default: 0.7)
        cognitive_weight: Cognitive weight c1 (default: 1.4)
        social_weight: Social weight c2 (default: 1.8)
    """
    if seed is not None:
        rng = random.Random(seed)
    else:
        rng = random

    if params is None:
        params = {}

    iterations = max(1, int(params.get("iterations", 1500)))
    particle_count = max(1, int(params.get("particle_count", 80)))
    inertia_weight = float(params.get("inertia_weight", 0.7))
    cognitive_weight = float(params.get("cognitive_weight", 1.4))
    social_weight = float(params.get("social_weight", 1.8))

    city_count = len(cities)

    # Initialize particles
    particles = []
    personal_best_paths = []

    for i in range(particle_count):
        p = list(range(city_count))
        if i > 0:
            rng.shuffle(p)
        else:
            # One particle starts with greedy
            p = _greedy_nearest_neighbor(cities, weights, start=0)
        particles.append(p)
        personal_best_paths.append(p.copy())

    # Evaluate initial
    best_idx = 0
    best_distance = float("inf")
    for i, p in enumerate(particles):
        d = calculate_distance(cities, p, weights)
        if d < best_distance:
            best_distance, best_idx = d, i
    best_path = particles[best_idx].copy()

    accept_worse_prob = 0.0

    for _ in range(iterations):
        gbest = best_path

        # Calculate adaptive probabilities
        total_cs = cognitive_weight + social_weight
        cognitive_prob = cognitive_weight / total_cs if total_cs > 0 else 0.5
        social_prob = social_weight / total_cs if total_cs > 0 else 0.5

        # Mutation steps based on stagnation
        mutation_steps = max(1, int(city_count * 0.02))

        for i, current in enumerate(particles):
            current_score = calculate_distance(cities, current, weights)

            # Cognitive component
            if rng.random() < cognitive_prob:
                candidate = guided_move_towards(current, personal_best_paths[i], rng)
            else:
                candidate = current.copy()

            # Social component
            if rng.random() < social_prob:
                candidate = guided_move_towards(candidate, gbest, rng)

            # Random perturbation
            for _ in range(mutation_steps):
                candidate = perturb_path(candidate, rng)

            # Local improvement
            candidate, candidate_score = two_opt_local_search(cities, candidate, rng, max_trials=2, weights=weights)

            # Accept if better (or with small probability if worse)
            if candidate_score < current_score or rng.random() < accept_worse_prob:
                particles[i] = candidate
                if candidate_score < calculate_distance(cities, personal_best_paths[i], weights):
                    personal_best_paths[i] = candidate.copy()
                if candidate_score < best_distance:
                    best_distance = candidate_score
                    best_path = candidate.copy()

    return best_distance, best_path


# ============================================================
# SA - Simulated Annealing
# ============================================================

def run_sa(cities, params=None, seed=None, weights=None):
    """Simulated Annealing for TSP.

    Parameters:
        initial_temp: Initial temperature (default: 1000.0)
        final_temp: Final temperature (default: 0.1)
        cooling_rate: Cooling rate (default: 0.95)
        inner_iterations: Inner loop iterations (default: 200)
    """
    if seed is not None:
        rng = random.Random(seed)
    else:
        rng = random

    if params is None:
        params = {}

    initial_temp = float(params.get("initial_temp", 1000.0))
    final_temp = float(params.get("final_temp", 0.1))
    cooling_rate = float(params.get("cooling_rate", 0.95))
    inner_iterations = int(params.get("inner_iterations", 200))

    n = len(cities)

    # Multiple starting candidates, pick best
    candidates = []
    for start in range(min(3, n)):
        p = _greedy_nearest_neighbor(cities, weights, start=start)
        candidates.append((calculate_distance(cities, p, weights), p))
    candidates.sort(key=lambda x: x[0])
    path = candidates[0][1].copy()

    current = path.copy()
    current_energy = calculate_distance(cities, current, weights)
    best_path = current.copy()
    best_energy = current_energy

    temperature = initial_temp

    while temperature > final_temp:
        for _ in range(inner_iterations):
            candidate = perturb_path(current, rng)
            candidate_energy = calculate_distance(cities, candidate, weights)
            delta = candidate_energy - current_energy

            if delta < 0 or rng.random() < math.exp(-delta / temperature):
                current = candidate
                current_energy = candidate_energy
                if current_energy < best_energy:
                    best_path = current.copy()
                    best_energy = current_energy

        temperature *= cooling_rate

    # Final polish
    polished, polished_energy = two_opt_local_search(cities, best_path, rng, max_trials=10, weights=weights)
    if polished_energy < best_energy:
        return polished_energy, polished
    return best_energy, best_path


# ============================================================
# GA - Genetic Algorithm
# ============================================================

def tournament_selection(scored, rng=None, k=3):
    """Tournament selection: pick k random, return best."""
    if rng is None:
        rng = random

    picks = rng.sample(scored, min(k, len(scored)))
    picks.sort(key=lambda item: item[0])
    return picks[0][1].copy()


def ordered_crossover(parent1, parent2, rng=None):
    """Ordered crossover (OX) for TSP."""
    if rng is None:
        rng = random

    n = len(parent1)
    start, end = sorted(rng.sample(range(n), 2))

    child = [None] * n
    child[start:end+1] = parent1[start:end+1]

    p2_idx = (end + 1) % n
    for i in range(n):
        val = parent2[(end + 1 + i) % n]
        if val not in child:
            child[p2_idx] = val
            p2_idx = (p2_idx + 1) % n

    return child


def mutate_path(path, mutation_rate, rng=None):
    """Mutate a path by swapping random cities."""
    if rng is None:
        rng = random

    out = path.copy()
    for i in range(len(path)):
        if rng.random() < mutation_rate:
            j = rng.randint(0, len(path) - 1)
            out[i], out[j] = out[j], out[i]
    return out


def _greedy_nearest_neighbor(cities, weights=None, start=0):
    """Greedy nearest neighbor heuristic starting from a city."""
    n = len(cities)
    rest = set(range(n))
    path = []

    current = start
    rest.discard(current)
    path.append(current)

    while rest:
        best = None
        best_d = math.inf
        for x in rest:
            d = edge_distance(cities, current, x, weights)
            if d < best_d:
                best_d, best = d, x
        current = best
        rest.remove(current)
        path.append(current)

    return path


def run_ga(cities, params=None, seed=None, weights=None):
    """Genetic Algorithm for TSP.

    Parameters:
        population_size: Population size (default: 80)
        generations: Number of generations (default: 300)
        crossover_rate: Crossover probability (default: 0.9)
        mutation_rate: Mutation probability (default: 0.2)
        elite_count: Number of elite individuals preserved (default: 2)
    """
    if seed is not None:
        rng = random.Random(seed)
    else:
        rng = random

    if params is None:
        params = {}

    population_size = max(2, int(params.get("population_size", 80)))
    generations = int(params.get("generations", 300))
    crossover_rate = float(params.get("crossover_rate", 0.9))
    mutation_rate = float(params.get("mutation_rate", 0.2))
    elite_count = int(params.get("elite_count", 2))

    n = len(cities)

    # Initialize population with diverse starts
    population = []
    for i in range(population_size):
        p = list(range(n))
        if i > 0:
            rng.shuffle(p)
        else:
            p = _greedy_nearest_neighbor(cities, weights, start=0)
        population.append(p)

    best_distance = float("inf")
    best_path = None

    for _ in range(generations):
        # Evaluate
        scored = []
        for path in population:
            d = calculate_distance(cities, path, weights)
            scored.append((d, path))
            if d < best_distance:
                best_distance = d
                best_path = path.copy()

        scored.sort(key=lambda item: item[0])

        # Improve using 2-opt
        improved = []
        for d, path in scored[:max(3, population_size // 10)]:
            polished, improved_dist = two_opt_local_search(cities, path, rng, max_trials=3, weights=weights)
            improved.append((improved_dist, polished))

        # Combine and keep best
        scored.extend(improved)
        scored.sort(key=lambda item: item[0])

        # Next generation
        next_population = []

        # Elitism
        for i in range(min(elite_count, len(scored))):
            next_population.append(scored[i][1].copy())

        # Fill rest with crossover + mutation
        seen = set()
        attempts = 0
        max_attempts = population_size * 10

        while len(next_population) < population_size and attempts < max_attempts:
            attempts += 1
            p1 = tournament_selection(scored, rng)
            p2 = tournament_selection(scored, rng)

            if rng.random() < crossover_rate:
                child = ordered_crossover(p1, p2, rng)
            else:
                child = p1.copy()

            child = mutate_path(child, mutation_rate, rng)

            key = tuple(child)
            if key not in seen:
                seen.add(key)
                next_population.append(child)

        # Fill remaining with random
        while len(next_population) < population_size:
            p = list(range(n))
            rng.shuffle(p)
            next_population.append(p)

        population = next_population

    return best_distance, best_path


# ============================================================
# ACA - Ant Colony Algorithm
# ============================================================

def select_next_city(current_city, unvisited, pheromone, eta, alpha, beta, rng=None):
    """Select next city using ant colony transition rule."""
    if rng is None:
        rng = random

    candidates = list(unvisited)
    probabilities = []

    for nxt in candidates:
        tau = pheromone[current_city][nxt] ** alpha
        h = eta[current_city][nxt] ** beta
        probabilities.append(tau * h)

    total = sum(probabilities)
    if total <= 0:
        return rng.choice(candidates)

    # Roulette wheel selection
    r = rng.random() * total
    cumulative = 0.0
    for city, prob in zip(candidates, probabilities):
        cumulative += prob
        if cumulative >= r:
            return city

    return candidates[-1]


def run_aca(cities, params=None, seed=None, weights=None):
    """Ant Colony Algorithm for TSP.

    Parameters:
        ant_count: Number of ants (default: 20)
        iterations: Iterations (default: 120)
        alpha: Pheromone factor (default: 1.0)
        beta: Heuristic factor (default: 4.0)
        evaporation: Evaporation rate ρ (default: 0.3)
        q: Pheromone intensity Q (default: 100.0)
    """
    if seed is not None:
        rng = random.Random(seed)
    else:
        rng = random

    if params is None:
        params = {}

    ant_count = max(2, int(params.get("ant_count", 20)))
    iterations = int(params.get("iterations", 120))
    alpha = float(params.get("alpha", 1.0))
    beta = float(params.get("beta", 4.0))
    evaporation = float(params.get("evaporation", 0.3))
    q_value = float(params.get("q", 100.0))

    n = len(cities)

    # Calculate heuristic (visibility) matrix
    eta = []
    for i in range(n):
        row = []
        for j in range(n):
            if i != j:
                distance = edge_distance(cities, i, j, weights)
                row.append(1.0 / max(distance, 1e-10))
            else:
                row.append(0.0)
        eta.append(row)

    # Initialize pheromone matrix
    tau_min = 1e-10
    tau_max = 1e10
    pheromone = []
    for i in range(n):
        pheromone.append([1.0] * n)

    best_distance = float("inf")
    best_path = None

    for _ in range(iterations):
        paths = []

        for _ in range(ant_count):
            start = rng.randint(0, n - 1)
            path = [start]
            unvisited = set(range(n))
            unvisited.remove(start)

            while unvisited:
                nxt = select_next_city(path[-1], unvisited, pheromone, eta, alpha, beta, rng)
                path.append(nxt)
                unvisited.remove(nxt)

            paths.append(path)

        # Evaluate paths
        scored = []
        for path in paths:
            d = calculate_distance(cities, path, weights)
            scored.append((d, path))
        scored.sort(key=lambda item: item[0])

        iter_best_path = scored[0][1]
        iter_best_dist = scored[0][0]

        if iter_best_dist < best_distance:
            best_distance = iter_best_dist
            best_path = iter_best_path.copy()

        # Evaporate pheromone
        for i in range(n):
            for j in range(n):
                pheromone[i][j] *= (1.0 - evaporation)

        # Deposit pheromone on the actual directed edges traversed (NOT symmetrized —
        # pheromone[b][a] is NOT updated, so direction information is preserved for ATSP).
        for dist, path in scored[:ant_count]:
            decay = q_value / max(dist, 1e-10)
            for a, b in zip(path, path[1:] + [path[0]]):
                pheromone[a][b] += decay

        # Elite reinforcement on best path (directed edges only)
        if best_path is not None:
            elite_delta = q_value / max(best_distance, 1e-10)
            for a, b in zip(best_path, best_path[1:] + [best_path[0]]):
                pheromone[a][b] += elite_delta

    return best_distance, best_path


# ============================================================
# TS - Tabu Search
# ============================================================

def run_ts(cities, params=None, seed=None, weights=None):
    """Tabu Search for TSP.

    Parameters:
        iterations: Iterations (default: 500)
        taboo_size: Tabu list size (default: 5)
        neighbor_count: Number of neighbors sampled (default: 400)
    """
    if seed is not None:
        rng = random.Random(seed)
    else:
        rng = random

    if params is None:
        params = {}

    n = len(cities)
    ts_iterations = int(params.get("iterations", 500))
    taboo_size = int(params.get("taboo_size", 5))
    neighbor_count = int(params.get("neighbor_count", 400))

    # Multiple starts
    best_candidates = []
    for i in range(min(3, n)):
        p = _greedy_nearest_neighbor(cities, weights, start=i)
        d = calculate_distance(cities, p, weights)
        best_candidates.append((d, p))
    best_candidates.sort(key=lambda p: p[0])

    current = best_candidates[0][1].copy()
    current_dist = calculate_distance(cities, current, weights)
    best_path = current.copy()
    best_distance = current_dist
    taboo = []

    for ts_iter in range(ts_iterations):
        neighbors = []
        move_set = []

        for _ in range(neighbor_count):
            i = rng.randint(0, n - 1)
            j = rng.randint(0, n - 1)
            if i == j:
                continue
            candidate = current.copy()
            candidate[i], candidate[j] = candidate[j], candidate[i]
            d = calculate_distance(cities, candidate, weights)
            m = (i, j) if i < j else (j, i)
            neighbors.append((d, candidate, m))

        neighbors.sort(key=lambda x: x[0])

        moved = False
        for d, candidate, m in neighbors:
            if d < best_distance or m not in taboo:
                current = candidate
                current_dist = d
                taboo.append(m)
                if len(taboo) > taboo_size:
                    taboo.pop(0)
                moved = True
                break

        if not moved and neighbors:
            d, candidate, _m = neighbors[0]
            current = candidate
            current_dist = d

        if current_dist < best_distance:
            best_distance = current_dist
            best_path = current.copy()

        # Occasional local optimization
        if ts_iter % 50 == 0 and best_path is not None:
            polished, polished_d = two_opt_local_search(cities, best_path, rng, max_trials=5, weights=weights)
            if polished_d < best_distance:
                best_distance = polished_d
                best_path = polished.copy()

    return best_distance, best_path


# ============================================================
# SOM - Self-Organizing Map
# ============================================================

def run_som(cities, params=None, seed=None, weights=None):
    """Self-Organizing Map for TSP.

    Parameters:
        iterations: Iterations (default: 8000)
        learning_rate: Learning rate (default: 0.8)
    """
    if seed is not None:
        rng = random.Random(seed)
    else:
        rng = random

    if params is None:
        params = {}

    n = len(cities)
    som_iterations = int(params.get("iterations", 8000))
    initial_lr = float(params.get("learning_rate", 0.8))

    # Extract city coordinates
    xs = [c.x for c in cities]
    ys = [c.y for c in cities]
    x_min, x_max = min(xs), max(xs)
    y_min, y_max = min(ys), max(ys)
    x_range = max(x_max - x_min, 1.0)
    y_range = max(y_max - y_min, 1.0)

    # Create neuron ring
    neuron_count = n * 3
    network = []
    for i in range(neuron_count):
        angle = 2.0 * math.pi * i / neuron_count
        cx = (x_min + x_max) / 2.0 + math.cos(angle) * x_range * 0.5
        cy = (y_min + y_max) / 2.0 + math.sin(angle) * y_range * 0.5
        network.append([cx, cy])

    lr = initial_lr
    radius = max(x_range, y_range)
    best_distance = float("inf")
    best_path = None

    for it in range(som_iterations):
        # Decay learning rate and radius
        progress = it / max(som_iterations, 1)
        lr = initial_lr * (1.0 - progress)
        radius = max(1.0, radius * 0.9995)

        # Pick a random city
        idx = rng.randint(0, n - 1)
        city = cities[idx]

        # Find closest neuron
        best_neuron = 0
        best_nd = float("inf")
        for k, (nx, ny) in enumerate(network):
            dx = city.x - nx
            dy = city.y - ny
            nd = dx * dx + dy * dy
            if nd < best_nd:
                best_nd = nd
                best_neuron = k

        # Update neurons in neighborhood
        for k, neuron in enumerate(network):
            # Circular distance along ring
            circ = min(abs(k - best_neuron), neuron_count - abs(k - best_neuron))
            if circ < radius:
                influence = math.exp(-circ * circ / (2.0 * radius * radius))
                factor = lr * influence
                neuron[0] += factor * (city.x - neuron[0])
                neuron[1] += factor * (city.y - neuron[1])

        # Evaluate after 75% completion
        if it > som_iterations * 0.75:
            # Map cities to neurons
            city_to_neuron = {}
            for c_idx, city in enumerate(cities):
                best_k = 0
                best_kd = float("inf")
                for k, (nx, ny) in enumerate(network):
                    dx = city.x - nx
                    dy = city.y - ny
                    kd = dx * dx + dy * dy
                    if kd < best_kd:
                        best_kd = kd
                        best_k = k
                city_to_neuron[c_idx] = best_k

            # Build tour by neuron order
            order = sorted(range(n), key=lambda c: city_to_neuron.get(c, 0))
            d = calculate_distance(cities, order, weights)
            if d < best_distance:
                best_distance = d
                best_path = order.copy()

    if best_path is not None:
        # SOM training is purely coordinate-based (Euclidean), so for directed /
        # non-Euclidean weights the initial path may be far from optimal.
        # Use a multi-start strategy: take the best of several 2-opt basins
        # — the SOM path, its reverse (ATSP: different cost), and greedy-NN
        # from EVERY starting city. This guarantees a minimum quality level
        # even when SOM's coordinate-based "map" doesn't match the weights.
        candidates = []
        candidates.append(two_opt_full(cities, best_path, weights=weights))
        candidates.append(two_opt_full(cities, best_path[::-1], weights=weights))
        for start in range(n):
            g_path = _greedy_nearest_neighbor(cities, weights, start=start)
            candidates.append(two_opt_full(cities, g_path, weights=weights))
        best_cand = min(candidates, key=lambda c: c[1])
        if best_cand[1] < best_distance:
            return best_cand[1], best_cand[0]
        return best_distance, best_path

    # Fallback: greedy
    p = _greedy_nearest_neighbor(cities, weights, start=0)
    return calculate_distance(cities, p, weights), p


# ============================================================
# DP - Dynamic Programming (Held-Karp, exact, supports ATSP)
# ============================================================

def run_dp(cities, params=None, seed=None, weights=None):
    """Held-Karp dynamic programming for TSP (supports asymmetric / directed TSP).

    Returns the optimal (shortest) Hamiltonian cycle. Complexity: O(n² · 2^n).
    For n > DP_MAX_CITIES, falls back to the Cheapest Insertion heuristic
    since Held-Karp's 2^n states become intractable.

    NOTE: This is the REAL Held-Karp algorithm using bitmask state compression
    (dp[mask][i] = min cost to start at 0, visit all cities in mask, end at i).
    The old implementation was mislabeled — it was actually a greedy insertion
    heuristic AND it symmetrized the distance matrix, silently discarding
    directed weights (0->1 vs 1->0). This version correctly handles ATSP.
    """
    n = len(cities)

    if n <= 2:
        path = list(range(n))
        return calculate_distance(cities, path, weights), path

    # Build asymmetric distance matrix — NO symmetrization, so directed
    # weights (e.g. 0->1 != 1->0) are respected during path selection.
    dist = [[0.0] * n for _ in range(n)]
    for i in range(n):
        for j in range(n):
            if i != j:
                dist[i][j] = edge_distance(cities, i, j, weights)

    # Held-Karp is O(n² · 2^n); fall back to heuristic for large n.
    if n > DP_MAX_CITIES:
        return _dp_cheapest_insertion(cities, weights, dist)

    # Held-Karp DP with bitmask state compression.
    # dp[mask][i] = min cost to start at city 0, visit all cities in mask, end at i.
    INF = math.inf
    full = (1 << n) - 1
    dp = [[INF] * n for _ in range(1 << n)]
    parent = [[-1] * n for _ in range(1 << n)]
    dp[1][0] = 0.0  # base case: only city 0 visited, end at 0

    for mask in range(1, 1 << n, 2):  # odd masks all contain city 0
        for i in range(n):
            if not (mask & (1 << i)):
                continue
            cur = dp[mask][i]
            if cur == INF:
                continue
            for j in range(n):
                if mask & (1 << j):
                    continue
                nm = mask | (1 << j)
                nc = cur + dist[i][j]
                if nc < dp[nm][j]:
                    dp[nm][j] = nc
                    parent[nm][j] = i

    # Close the tour: pick the end city that minimises total cycle cost.
    best_cost = INF
    best_end = -1
    for i in range(1, n):
        c = dp[full][i]
        if c == INF:
            continue
        total = c + dist[i][0]
        if total < best_cost:
            best_cost = total
            best_end = i

    if best_end == -1:
        # Should not happen for a complete graph — safety net.
        path = list(range(n))
        return calculate_distance(cities, path, weights), path

    # Reconstruct path 0 -> ... -> best_end (calculate_distance closes the loop).
    path = []
    mask = full
    cur = best_end
    while cur != -1:
        path.append(cur)
        prev = parent[mask][cur]
        mask ^= (1 << cur)
        cur = prev
    path.reverse()

    total = calculate_distance(cities, path, weights)
    return total, path


def _dp_cheapest_insertion(cities, weights, dist):
    """Cheapest Insertion heuristic fallback for large n.

    Uses the asymmetric distance matrix (no symmetrization) so directed
    weights are at least respected during path construction — unlike the
    old implementation which silently discarded half the directed edges.
    This is a heuristic, NOT optimal; it only runs when n > DP_MAX_CITIES.
    """
    n = len(dist)
    # Seed tour: the two cities with the largest single-direction edge.
    max_d = -1.0
    path = [0, 1]
    for i in range(n):
        for j in range(n):
            if i != j and dist[i][j] > max_d:
                max_d = dist[i][j]
                path = [i, j]

    inserted = set(path)
    while len(inserted) < n:
        best_pos = None
        best_delta = math.inf
        for c in range(n):
            if c in inserted:
                continue
            for i in range(len(path)):
                j = (i + 1) % len(path)
                a, b = path[i], path[j]
                delta = dist[a][c] + dist[c][b] - dist[a][b]
                if delta < best_delta:
                    best_delta = delta
                    best_pos = (c, i + 1)
        if best_pos is None:
            break
        c, pos = best_pos
        path.insert(pos, c)
        inserted.add(c)

    total = calculate_distance(cities, path, weights)
    return total, path


# ============================================================
# BNB - Branch and Bound
# ============================================================

def run_bnb(cities, params=None, seed=None, weights=None):
    """Branch and Bound for exact TSP solution.

    Only feasible for small instances (≤ 15 cities).
    """
    n = len(cities)

    if n > BNB_MAX_CITIES:
        raise ValueError(f"分支定界仅支持 ≤{BNB_MAX_CITIES} 个城市")

    # Build asymmetric distance matrix — NO symmetrization, so directed
    # weights (e.g. 0->1 != 1->0) are respected during branch-and-bound.
    dist = [[0.0] * n for _ in range(n)]
    for i in range(n):
        for j in range(n):
            if i != j:
                dist[i][j] = edge_distance(cities, i, j, weights)

    def lower_bound(path):
        """Lower bound: partial path length + MST of remaining."""
        partial = 0.0
        for a, b in zip(path, path[1:]):
            partial += dist[a][b]

        in_path = set(path)
        last = path[-1] if path else 0
        candidates = []

        nodes = [last]
        rest = [v for v in range(n) if v not in in_path]
        nodes.extend(rest)

        if len(nodes) <= 1:
            if path and path[0] not in in_path.union({last}):
                pass
            return partial

        # Use MST as lower bound for remaining nodes
        mst_nodes = nodes.copy()
        if len(mst_nodes) <= 1:
            return partial

        visited = {mst_nodes[0]}
        mst_cost = 0.0
        remaining = set(mst_nodes[1:])

        while remaining:
            best = math.inf
            best_v = None
            for u in visited:
                for v in remaining:
                    d = dist[u][v] if u < n and v < n else 0
                    if v < n and u < n:
                        d = dist[u][v]
                    else:
                        continue
                    if d < best:
                        best = d
                        best_v = v
            if best_v is None:
                break
            mst_cost += best
            visited.add(best_v)
            remaining.remove(best_v)

        return partial + mst_cost

    # Branch and bound using priority queue
    best_distance = math.inf
    best_path = None
    counter = 0

    heap = []

    # Multiple starting points
    for start in range(min(3, n)):
        init_bound = lower_bound([start])
        heapq.heappush(heap, (init_bound, 0, [start]))

    while heap:
        bound, _, path = heapq.heappop(heap)

        if bound >= best_distance:
            continue

        if len(path) == n:
            # Complete tour
            total = calculate_distance(cities, path, weights)
            if total < best_distance:
                best_distance = total
                best_path = path.copy()
            continue

        # Expand path
        current = path[-1] if path else 0
        for nxt in range(n):
            if nxt not in path:
                new_path = path + [nxt]
                new_bound = lower_bound(new_path)
                if new_bound < best_distance:
                    heapq.heappush(heap, (new_bound, counter, new_path))
                    counter += 1

    if best_path is None:
        # Fallback
        return run_dp(cities, params, seed, weights)

    return best_distance, best_path


def format_path(path):
    """Format a path as '城市1 -> 城市2 -> ... -> 城市1'."""
    labels = [f"城市{idx + 1}" for idx in path]
    start_label = f"城市{path[0] + 1}"
    return " -> ".join(labels[1:] + [start_label])

