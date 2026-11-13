#!/usr/bin/env python3
"""Reproduce a leakage-aware OpenFootball Elo vs time-decayed Poisson experiment.

Input files are read from a local clone of openfootball/football.json. No raw match
data is copied into this repository. Results are forecast at 00:00 UTC on the
fixture date because the source carries local dates/times without a uniform timezone.
Each result is treated as available at 00:00 UTC two days after its match date; only
results with availability strictly before a target date may enter its forecast.

Example:
  python3 research/football_4_model_experiment.py \
    --data-root /tmp/fi-football-json --output /tmp/model-report.json
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import math
import random
import subprocess
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, List, Mapping, Optional, Sequence, Tuple


OUTCOMES = ("H", "D", "A")
LEAGUES = {
    "en.1": "English Premier League",
    "de.1": "Bundesliga",
    "es.1": "La Liga",
    "it.1": "Serie A",
    "fr.1": "Ligue 1",
}
VALIDATION_START = dt.date(2022, 7, 1)
HOLDOUT_START = dt.date(2024, 7, 1)
EVALUATION_END = dt.date(2026, 7, 1)
HALF_LIVES_DAYS: Tuple[Optional[int], ...] = (None, 180, 365, 730)
MINIMUM_COMMON_VALIDATION = 500
MINIMUM_COMMON_HOLDOUT = 1_000
BOOTSTRAP_REPLICATES = 2_000

Probabilities = Tuple[float, float, float]


@dataclass(frozen=True)
class Match:
    league_code: str
    league_name: str
    date: dt.date
    home: str
    away: str
    home_goals: int
    away_goals: int

    @property
    def availability_day(self) -> int:
        return self.date.toordinal() + 2

    @property
    def outcome_index(self) -> int:
        if self.home_goals > self.away_goals:
            return 0
        if self.home_goals == self.away_goals:
            return 1
        return 2


@dataclass
class PredictionRow:
    league_code: str
    league_name: str
    date: dt.date
    observed: int
    elo: Optional[Probabilities]
    frequency: Optional[Probabilities]
    poisson_cumulative: Optional[Probabilities]
    poisson_by_half_life: Dict[str, Optional[Probabilities]]


def _valid_team(value: object) -> bool:
    if not isinstance(value, str) or not value.strip():
        return False
    normalized = value.strip().casefold()
    return not any(token in normalized for token in ("dummy", "unknown team", "tbd", "empty stands"))


def _full_time_score(match: Mapping[str, object]) -> Optional[Tuple[int, int]]:
    score = match.get("score")
    if isinstance(score, dict):
        score = score.get("ft")
    if not isinstance(score, list) or len(score) != 2:
        return None
    home, away = score
    if type(home) is not int or type(away) is not int or home < 0 or away < 0:
        return None
    return home, away


def load_matches(data_root: Path) -> Tuple[Dict[str, List[Match]], Dict[str, object]]:
    by_league: Dict[str, Dict[Tuple[dt.date, str, str], Match]] = defaultdict(dict)
    conflicts = set()
    files_seen = 0
    rows_seen = 0
    incomplete = 0

    for code, league_name in LEAGUES.items():
        for path in sorted(data_root.glob("*/" + code + ".json")):
            files_seen += 1
            try:
                payload = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                continue
            rows = payload.get("matches", []) if isinstance(payload, dict) else []
            if not isinstance(rows, list):
                continue
            for raw in rows:
                rows_seen += 1
                if not isinstance(raw, dict):
                    incomplete += 1
                    continue
                score = _full_time_score(raw)
                home = raw.get("team1")
                away = raw.get("team2")
                try:
                    match_date = dt.date.fromisoformat(str(raw.get("date", "")))
                except ValueError:
                    match_date = None
                if score is None or match_date is None or not _valid_team(home) or not _valid_team(away):
                    incomplete += 1
                    continue
                if str(home).strip().casefold() == str(away).strip().casefold():
                    incomplete += 1
                    continue
                if match_date >= EVALUATION_END:
                    continue
                match = Match(code, league_name, match_date, str(home).strip(), str(away).strip(), score[0], score[1])
                key = (match_date, match.home.casefold(), match.away.casefold())
                previous = by_league[code].get(key)
                if previous is not None and (previous.home_goals, previous.away_goals) != score:
                    conflicts.add((code, key))
                elif previous is None:
                    by_league[code][key] = match

    for code, key in conflicts:
        by_league[code].pop(key, None)

    grouped = {
        code: sorted(rows.values(), key=lambda m: (m.date, m.home.casefold(), m.away.casefold()))
        for code, rows in by_league.items()
    }
    revision = "unknown"
    revision_time = "unknown"
    try:
        revision = subprocess.check_output(
            ["git", "-C", str(data_root), "rev-parse", "HEAD"], text=True, stderr=subprocess.DEVNULL,
        ).strip()
        revision_time = subprocess.check_output(
            ["git", "-C", str(data_root), "show", "-s", "--format=%cI", "HEAD"],
            text=True,
            stderr=subprocess.DEVNULL,
        ).strip()
    except (OSError, subprocess.CalledProcessError):
        pass
    metadata = {
        "source": "openfootball/football.json",
        "source_url": "https://github.com/openfootball/football.json",
        "license": "CC0-1.0 / public domain (upstream README)",
        "source_revision": revision,
        "source_commit_time": revision_time,
        "source_files_seen": files_seen,
        "rows_seen": rows_seen,
        "incomplete_or_invalid_rows_skipped": incomplete,
        "conflicting_duplicate_fixtures_removed": len(conflicts),
        "completed_matches_by_league": {code: len(rows) for code, rows in grouped.items()},
        "date_cutoff_rule": "forecast at 00:00 UTC on fixture date; source result assumed available 00:00 UTC on date+2; strict availability < forecast cutoff",
        "source_vintage_limit": "one current repository snapshot; no per-match historical publication vintages",
    }
    return grouped, metadata


def _expected_home_score(home_rating: float, away_rating: float) -> float:
    return 1.0 / (1.0 + 10.0 ** ((away_rating - home_rating - 65.0) / 400.0))


class EloState:
    def __init__(self) -> None:
        self.ratings: Dict[str, float] = {}
        self.played: Dict[str, int] = defaultdict(int)
        self.matches = 0
        self.draws = 0
        self.result_counts = [0, 0, 0]

    def add_batch(self, batch: Sequence[Match]) -> None:
        deltas: Dict[str, float] = defaultdict(float)
        for match in batch:
            home = match.home.casefold()
            away = match.away.casefold()
            home_rating = self.ratings.get(home, 1500.0)
            away_rating = self.ratings.get(away, 1500.0)
            expected = _expected_home_score(home_rating, away_rating)
            actual = 1.0 if match.home_goals > match.away_goals else 0.0 if match.home_goals < match.away_goals else 0.5
            delta = 20.0 * (actual - expected)
            deltas[home] += delta
            deltas[away] -= delta
            self.played[home] += 1
            self.played[away] += 1
            self.matches += 1
            self.draws += int(actual == 0.5)
            self.result_counts[match.outcome_index] += 1
        for team, delta in deltas.items():
            self.ratings[team] = self.ratings.get(team, 1500.0) + delta

    def forecast(self, home_name: str, away_name: str) -> Optional[Probabilities]:
        home, away = home_name.casefold(), away_name.casefold()
        if self.matches < 10 or self.played[home] < 3 or self.played[away] < 3:
            return None
        expected = _expected_home_score(self.ratings.get(home, 1500.0), self.ratings.get(away, 1500.0))
        draw_rate = (self.draws + 0.25 * 4.0) / (self.matches + 4.0)
        draw = min(draw_rate, 2.0 * min(expected, 1.0 - expected))
        return (max(0.0, expected - draw / 2.0), draw, max(0.0, 1.0 - expected - draw / 2.0))

    def frequency(self) -> Probabilities:
        denominator = sum(self.result_counts) + 3.0
        return tuple((count + 1.0) / denominator for count in self.result_counts)  # type: ignore[return-value]


class GoalStats:
    """Weighted same-league attack/concession rates with eight-match shrinkage."""

    def __init__(self, half_life_days: Optional[int]) -> None:
        self.half_life_days = half_life_days
        self.last_day: Optional[int] = None
        self.actual_matches = 0
        self.weighted_matches = 0.0
        self.home_goals = 0.0
        self.away_goals = 0.0
        # [weighted appearances, goals for, goals against, actual appearances]
        self.teams: Dict[str, List[float]] = defaultdict(lambda: [0.0, 0.0, 0.0, 0.0])

    def decay_to(self, day: int) -> None:
        if self.last_day is not None:
            if day < self.last_day:
                raise ValueError("Goal state cannot move backward in time")
            if self.half_life_days is not None:
                factor = math.exp(-math.log(2.0) * (day - self.last_day) / self.half_life_days)
                self.weighted_matches *= factor
                self.home_goals *= factor
                self.away_goals *= factor
                for totals in self.teams.values():
                    totals[0] *= factor
                    totals[1] *= factor
                    totals[2] *= factor
        self.last_day = day

    def add_batch(self, batch: Sequence[Match]) -> None:
        for match in batch:
            home, away = match.home.casefold(), match.away.casefold()
            home_stats, away_stats = self.teams[home], self.teams[away]
            self.actual_matches += 1
            self.weighted_matches += 1.0
            self.home_goals += match.home_goals
            self.away_goals += match.away_goals
            home_stats[0] += 1.0
            home_stats[1] += match.home_goals
            home_stats[2] += match.away_goals
            home_stats[3] += 1.0
            away_stats[0] += 1.0
            away_stats[1] += match.away_goals
            away_stats[2] += match.home_goals
            away_stats[3] += 1.0

    def forecast(self, home_name: str, away_name: str) -> Optional[Probabilities]:
        home = self.teams.get(home_name.casefold())
        away = self.teams.get(away_name.casefold())
        if self.actual_matches < 50 or self.weighted_matches <= 0.0 or home is None or away is None:
            return None
        if home[3] < 5.0 or away[3] < 5.0:
            return None
        league_home = self.home_goals / self.weighted_matches
        league_away = self.away_goals / self.weighted_matches
        league_team = (self.home_goals + self.away_goals) / (2.0 * self.weighted_matches)
        if min(league_home, league_away, league_team) <= 0.0:
            return None

        def attack(stats: Sequence[float]) -> float:
            return ((stats[1] + league_team * 8.0) / (stats[0] + 8.0)) / league_team

        def defense(stats: Sequence[float]) -> float:
            return ((stats[2] + league_team * 8.0) / (stats[0] + 8.0)) / league_team

        home_lambda = min(5.0, max(0.05, league_home * attack(home) * defense(away)))
        away_lambda = min(5.0, max(0.05, league_away * attack(away) * defense(home)))
        return poisson_1x2(home_lambda, away_lambda)


def poisson_1x2(home_lambda: float, away_lambda: float, max_goals: int = 10) -> Probabilities:
    def masses(lam: float) -> List[float]:
        values = [math.exp(-lam)]
        for goals in range(1, max_goals + 1):
            values.append(values[-1] * lam / goals)
        return values

    home_mass, away_mass = masses(home_lambda), masses(away_lambda)
    weights = [0.0, 0.0, 0.0]
    for hg in range(max_goals + 1):
        for ag in range(max_goals + 1):
            value = home_mass[hg] * away_mass[ag]
            outcome = 0 if hg > ag else 1 if hg == ag else 2
            weights[outcome] += value
    total = sum(weights)
    return (weights[0] / total, weights[1] / total, weights[2] / total)


def _half_life_key(value: Optional[int]) -> str:
    return "none" if value is None else str(value)


def evaluate_league(matches: Sequence[Match]) -> List[PredictionRow]:
    ordered = sorted(matches, key=lambda m: (m.availability_day, m.date, m.home.casefold(), m.away.casefold()))
    targets: Dict[int, List[Match]] = defaultdict(list)
    for match in matches:
        if VALIDATION_START <= match.date < EVALUATION_END:
            targets[match.date.toordinal()].append(match)
    states = {half_life: GoalStats(half_life) for half_life in HALF_LIVES_DAYS}
    elo = EloState()
    rows: List[PredictionRow] = []
    history_index = 0

    for target_day, day_matches in sorted(targets.items()):
        while history_index < len(ordered) and ordered[history_index].availability_day < target_day:
            available_day = ordered[history_index].availability_day
            end = history_index + 1
            while end < len(ordered) and ordered[end].availability_day == available_day:
                end += 1
            batch = ordered[history_index:end]
            elo.add_batch(batch)
            for state in states.values():
                state.decay_to(available_day)
                state.add_batch(batch)
            history_index = end

        for state in states.values():
            state.decay_to(target_day)
        for match in sorted(day_matches, key=lambda m: (m.home.casefold(), m.away.casefold())):
            rows.append(PredictionRow(
                league_code=match.league_code,
                league_name=match.league_name,
                date=match.date,
                observed=match.outcome_index,
                elo=elo.forecast(match.home, match.away),
                frequency=elo.frequency() if elo.matches >= 10 else None,
                poisson_cumulative=states[None].forecast(match.home, match.away),
                poisson_by_half_life={
                    _half_life_key(half_life): state.forecast(match.home, match.away)
                    for half_life, state in states.items()
                },
            ))
    return rows


def _score(rows: Sequence[PredictionRow], prediction_getter) -> Optional[Dict[str, float]]:
    scored = [(row, prediction_getter(row)) for row in rows]
    scored = [(row, prediction) for row, prediction in scored if prediction is not None]
    if not scored:
        return None
    brier_values = [sum((p - (1.0 if i == row.observed else 0.0)) ** 2 for i, p in enumerate(prediction)) for row, prediction in scored]
    log_losses = [-math.log(max(prediction[row.observed], 1e-15)) for row, prediction in scored]
    accuracy = [int(max(range(3), key=lambda i: prediction[i]) == row.observed) for row, prediction in scored]
    eces = []
    bins = 10
    for outcome in range(3):
        class_gap = 0.0
        for bin_index in range(bins):
            low, high = bin_index / bins, (bin_index + 1) / bins
            members = [(prediction[outcome], int(row.observed == outcome)) for row, prediction in scored
                       if low <= prediction[outcome] < high or (bin_index == bins - 1 and prediction[outcome] == 1.0)]
            if members:
                class_gap += len(members) / len(scored) * abs(
                    sum(p for p, _ in members) / len(members) - sum(y for _, y in members) / len(members)
                )
        eces.append(class_gap)
    return {
        "n": float(len(scored)),
        "brier": sum(brier_values) / len(scored),
        "log_loss": sum(log_losses) / len(scored),
        "top1_accuracy": sum(accuracy) / len(scored),
        "macro_one_vs_rest_ece_10_bins": sum(eces) / 3.0,
    }


def _bootstrap_difference(rows: Sequence[PredictionRow], half_life_key: str) -> Dict[str, object]:
    # Resample league x 7-day blocks so fixtures in one league-week stay together.
    clusters: Dict[Tuple[str, int], List[PredictionRow]] = defaultdict(list)
    for row in rows:
        candidate = row.poisson_by_half_life.get(half_life_key)
        if row.elo is None or candidate is None:
            continue
        clusters[(row.league_code, row.date.toordinal() // 7)].append(row)
    cluster_stats = []
    for grouped in clusters.values():
        brier_delta = 0.0
        log_delta = 0.0
        for row in grouped:
            candidate = row.poisson_by_half_life[half_life_key]
            assert row.elo is not None and candidate is not None
            brier_delta += sum((candidate[i] - (1.0 if i == row.observed else 0.0)) ** 2 for i in range(3))
            brier_delta -= sum((row.elo[i] - (1.0 if i == row.observed else 0.0)) ** 2 for i in range(3))
            log_delta += -math.log(max(candidate[row.observed], 1e-15))
            log_delta -= -math.log(max(row.elo[row.observed], 1e-15))
        cluster_stats.append((len(grouped), brier_delta, log_delta))
    if not cluster_stats:
        return {"clusters": 0, "replicates": 0, "brier_delta_95_ci": None, "log_loss_delta_95_ci": None}
    rng = random.Random(20261113)
    brier_distribution, log_distribution = [], []
    for _ in range(BOOTSTRAP_REPLICATES):
        sampled = [cluster_stats[rng.randrange(len(cluster_stats))] for _ in cluster_stats]
        count = sum(row[0] for row in sampled)
        if count:
            brier_distribution.append(sum(row[1] for row in sampled) / count)
            log_distribution.append(sum(row[2] for row in sampled) / count)

    def interval(values: Sequence[float]) -> List[float]:
        ordered = sorted(values)
        return [ordered[int(0.025 * (len(ordered) - 1))], ordered[int(0.975 * (len(ordered) - 1))]]

    paired = [row for row in rows if row.elo is not None and row.poisson_by_half_life.get(half_life_key) is not None]
    candidate_score = _score(paired, lambda row: row.poisson_by_half_life[half_life_key])
    elo_score = _score(paired, lambda row: row.elo)
    assert candidate_score is not None and elo_score is not None
    return {
        "clusters": len(cluster_stats),
        "cluster_definition": "competition x 7-day UTC-date block; repeated-team dependence across blocks remains",
        "replicates": BOOTSTRAP_REPLICATES,
        "candidate_minus_elo_brier": candidate_score["brier"] - elo_score["brier"],
        "candidate_minus_elo_log_loss": candidate_score["log_loss"] - elo_score["log_loss"],
        "brier_delta_95_ci": interval(brier_distribution),
        "log_loss_delta_95_ci": interval(log_distribution),
    }


def run_experiment(data_root: Path) -> Dict[str, object]:
    grouped, metadata = load_matches(data_root)
    all_rows: List[PredictionRow] = []
    for code in LEAGUES:
        all_rows.extend(evaluate_league(grouped.get(code, [])))

    validation_rows = [row for row in all_rows if VALIDATION_START <= row.date < HOLDOUT_START]
    holdout_rows = [row for row in all_rows if HOLDOUT_START <= row.date < EVALUATION_END]
    validation_common = [row for row in validation_rows
                         if row.elo is not None and all(value is not None for value in row.poisson_by_half_life.values())]
    holdout_common = [row for row in holdout_rows
                      if row.elo is not None and all(value is not None for value in row.poisson_by_half_life.values())]

    validation_candidates = []
    for half_life in HALF_LIVES_DAYS:
        key = _half_life_key(half_life)
        metrics = _score(validation_common, lambda row, model_key=key: row.poisson_by_half_life[model_key])
        validation_candidates.append({"half_life_days": half_life, "metrics_on_common_rows": metrics})
    eligible_candidates = [candidate for candidate in validation_candidates
                           if candidate["metrics_on_common_rows"] is not None]
    selected = min(eligible_candidates, key=lambda candidate: (
        candidate["metrics_on_common_rows"]["log_loss"], candidate["metrics_on_common_rows"]["brier"],
        float("inf") if candidate["half_life_days"] is None else candidate["half_life_days"],
    )) if eligible_candidates else None
    selected_key = _half_life_key(selected["half_life_days"]) if selected else None

    selected_holdout = _score(holdout_common, lambda row: row.poisson_by_half_life[selected_key]) if selected_key else None
    elo_holdout = _score(holdout_common, lambda row: row.elo)
    cumulative_holdout = _score(holdout_common, lambda row: row.poisson_cumulative)
    frequency_holdout = _score(holdout_common, lambda row: row.frequency)
    selected_ci = _bootstrap_difference(holdout_common, selected_key) if selected_key else {}
    enough_data = len(validation_common) >= MINIMUM_COMMON_VALIDATION and len(holdout_common) >= MINIMUM_COMMON_HOLDOUT
    both_scores_better = bool(
        selected_holdout and elo_holdout and
        selected_holdout["brier"] < elo_holdout["brier"] and
        selected_holdout["log_loss"] < elo_holdout["log_loss"]
    )
    intervals_better = bool(
        selected_ci.get("brier_delta_95_ci") and selected_ci.get("log_loss_delta_95_ci") and
        selected_ci["brier_delta_95_ci"][1] < 0.0 and selected_ci["log_loss_delta_95_ci"][1] < 0.0
    )

    by_league = {}
    for code, name in LEAGUES.items():
        rows = [row for row in holdout_common if row.league_code == code]
        by_league[code] = {
            "name": name,
            "n": len(rows),
            "elo": _score(rows, lambda row: row.elo),
            "selected_poisson": _score(rows, lambda row: row.poisson_by_half_life[selected_key]) if selected_key else None,
        }

    return {
        "experiment": "football-intelligence-4.0 / current Elo vs validation-selected exponentially weighted goal Poisson",
        "data": metadata,
        "split": {
            "validation_start_inclusive": VALIDATION_START.isoformat(),
            "holdout_start_inclusive": HOLDOUT_START.isoformat(),
            "evaluation_end_exclusive": EVALUATION_END.isoformat(),
            "validation_target_rows": len(validation_rows),
            "validation_common_rows": len(validation_common),
            "holdout_target_rows": len(holdout_rows),
            "holdout_common_rows": len(holdout_common),
        },
        "selection": {
            "metric": "validation Log Loss, Brier score tie-break",
            "half_life_grid_days": list(HALF_LIVES_DAYS),
            "selected_half_life_days": selected["half_life_days"] if selected else None,
            "minimum_common_validation": MINIMUM_COMMON_VALIDATION,
            "validation_candidates": validation_candidates,
        },
        "holdout_common_cohort": {
            "current_production_elo": elo_holdout,
            "same_competition_frequency": frequency_holdout,
            "existing_cumulative_goal_poisson": cumulative_holdout,
            "validation_selected_time_decay_poisson": selected_holdout,
            "selected_poisson_minus_elo_week_block_bootstrap": selected_ci,
            "by_league": by_league,
        },
        "promotion_gate": {
            "minimum_common_holdout": MINIMUM_COMMON_HOLDOUT,
            "enough_data": enough_data,
            "lower_brier_and_log_loss_than_elo": both_scores_better,
            "both_week_block_95_percent_intervals_below_zero": intervals_better,
            "candidate_passes": enough_data and both_scores_better and intervals_better,
        },
        "limitations": [
            "This is a single current source snapshot, not an archive of what each source knew at each historical date.",
            "Date-only results use a conservative +2-day availability proxy; it is a modeling assumption, not an observed publication timestamp.",
            "OpenFootball coverage and team naming are uneven across leagues/seasons; results are not xG, lineups, or news.",
            "Week-block intervals group same-league nearby fixtures but recurring-team dependence remains.",
            "A historical holdout pass is not prospective validation or evidence of future betting profit.",
        ],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data-root", required=True, type=Path, help="Local clone of openfootball/football.json")
    parser.add_argument("--output", type=Path, help="Optional path for the JSON report")
    args = parser.parse_args()
    report = run_experiment(args.data_root)
    rendered = json.dumps(report, ensure_ascii=False, indent=2)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered + "\n", encoding="utf-8")
    print(rendered)


if __name__ == "__main__":
    main()
