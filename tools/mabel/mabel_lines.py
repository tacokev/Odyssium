"""Writes what Mabel, the app's DJ, knows and says about every episode: its "vibes" (story arc,
characters, themes, mood, season) and a few lines she can say right before playing it. Then
groups every episode's tags into the vibes she switches between, with her lines for those, and
packs it all into mabel.json for the app.

Describing the episodes uses Claude (the Anthropic API; set ANTHROPIC_API_KEY, or put it in a
.env next to this script) or a local model in Ollama (--ollama MODEL).

  python mabel_lines.py episodes --sample 8      # a few episodes, to read the lines first
  python mabel_lines.py episodes                 # every episode (skips ones already done)
  python mabel_lines.py episodes --batch         # every episode through the Batch API (half price)
  python mabel_lines.py episodes --ollama qwen3:14b
  python mabel_lines.py vibes                    # a first draft of vibes.json from the tags
  python mabel_lines.py build                    # -> the app's assets/mabel.json
  python mabel_lines.py show                     # print what's been written, to read through

The data lives outside the repo, in the folder MABEL_DATA names (default: this script's folder):
its transcripts/*.json, catalog.json, soda_cast.json and club_characters.json are the inputs, and
each episode's result is kept in its mabel/episodes/<content id>.json, so re-runs only do what's
missing.

vibes.json next to this script is the vibes and Mabel's lines, edited by hand; the vibes command
only writes a draft of it to the data folder (mabel/vibes.json), to start from.
"""
import argparse
import json
import os
import random
import re
import sys
import time
from collections import Counter
from pathlib import Path

import anthropic

HERE = Path(__file__).resolve().parent
DATA = Path(os.environ.get("MABEL_DATA", HERE))
OUT = DATA / "mabel"
EPISODES_OUT = OUT / "episodes"
VIBES = HERE / "vibes.json"
# In the repo this is tools/mabel/, and the app reads its built-in lines from its assets.
APP_ASSET = HERE.parent.parent / "app" / "src" / "main" / "assets" / "mabel.json"

MODEL = "claude-opus-5-5"

PERSONA = """You write lines for Mabel, the DJ of an Adventures in Odyssey listening app.

Mabel is the voice-activated computer John Avery Whittaker built in the computer room at Whit's \
End (first heard in "A Bite of Applesauce", where she greets him with "Hello, John Avery \
Whittaker."). In the app she hosts a listening session like a radio DJ: she picks episodes that \
go together into a "vibe", introduces them, and now and then switches to a new vibe ("Alright, \
let's switch it up...").

Her voice: warm, upbeat and friendly, with a light touch of computer precision (she might \
mention her "files" or "records", but rarely - it's seasoning, not a gimmick). Short, natural \
spoken sentences; she's heard, not read, by kids and families. Never sarcastic or snarky.

Everything she says is spoken by a text-to-speech voice, so:
- Plain words only: no emoji, no symbols, no markdown, no parentheses, no abbreviations she \
couldn't say aloud. Write numbers as a person would say them.
- Episode titles exactly as given, without quotes or a number.
- No spoilers: tease the setup, never how it turns out."""

EPISODE_INSTRUCTIONS = """Below is one episode: its details and its full transcript (a machine \
transcription; speaker names were guessed by voice, "?" means unknown, and the transcript can \
mishear words). Read it, then describe the episode for Mabel.

- summary: one spoiler-free sentence about the setup.
- arc: the name fans would use for the multi-episode storyline this belongs to (for example \
"the Novacom saga" or "the Blackgaard saga"), or null if it's a standalone story. Only name an \
arc you're confident of.
- main_characters: the two to four characters the story is really about, by the names the show \
uses.
- setting: where most of it happens, in a few words (for example "Whit's End", "the \
Imagination Station, ancient Egypt", "Connellsville").
- themes: one to four lessons or ideas it's about, a word or two each, lowercase.
- moods: one to three, from the list.
- season: the holiday or season it's tied to, if it clearly is one.
- tags: four to eight short lowercase tags someone might build a playlist around: characters \
("eugene", "connie and eugene"), places ("imagination station", "campbell county"), arcs \
("novacom"), kinds of story ("mystery", "bible story", "history", "detective harlow doyle", \
"sci-fi", "sports"), recurring things ("kids radio", "the room of consequence"). Reuse common \
names so episodes match each other.
- intros: three different lines Mabel says right before she plays this episode, one or two \
sentences each, at most thirty words. Every one of them says the title, exactly as given, and \
teases the setup in a way that makes you want to hear it. For a later part of a multi-part \
story, mention which part it is. Vary how they start; don't begin with "Up next" more than once."""

MOODS = ["funny", "heartfelt", "suspenseful", "mysterious", "adventurous", "spooky",
         "historical", "biblical", "dramatic", "thoughtful", "action-packed"]
SEASONS = ["christmas", "easter", "thanksgiving", "halloween", "new year", "valentines day",
           "fourth of july", "summer", "fall", "winter", "spring", "back to school"]

EPISODE_SCHEMA = {
    "type": "object",
    "properties": {
        "summary": {"type": "string"},
        "arc": {"anyOf": [{"type": "string"}, {"type": "null"}]},
        "main_characters": {"type": "array", "items": {"type": "string"}},
        "setting": {"type": "string"},
        "themes": {"type": "array", "items": {"type": "string"}},
        "moods": {"type": "array", "items": {"type": "string", "enum": MOODS}},
        "season": {"anyOf": [{"type": "string", "enum": SEASONS}, {"type": "null"}]},
        "tags": {"type": "array", "items": {"type": "string"}},
        "intros": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["summary", "arc", "main_characters", "setting", "themes", "moods", "season",
                 "tags", "intros"],
    "additionalProperties": False,
}

VIBE_INSTRUCTIONS = """Below is every tag, arc, mood and season used across all the episodes, \
with how many episodes have each. Group them into the "vibes" Mabel switches between during a \
listening session: around forty to seventy of them, each one something that makes a good run \
of two to four episodes in a row (a story arc, a character or pair, a place, a kind of story, a \
mood, a season). Leave out tags too vague or too rare to build a run on.

For each vibe:
- id: short, lowercase, hyphenated.
- name: how it reads on screen ("Novacom", "Eugene and Bernard", "Spooky stories").
- spoken: how Mabel says it in "let's switch it up with ___" (for example "a Novacom vibe", \
"some Eugene and Bernard", "something spooky").
- members: the exact tags, arcs, moods or seasons from the list that put an episode in this \
vibe.
- switch_lines: four different lines Mabel says when she switches to this vibe, one or two \
short sentences each; they lead into an episode intro, so don't name an episode.
- ordered: true if its episodes should play in story order (an arc), false if any order works.

Also write:
- openers: six lines Mabel says to start a listening session, before the first vibe (she can \
say hello, like she greets Whit). Short.
- part_lines: four lines for when the next episode is the next part of the story just played \
("And now, part two."); use {part} where the part number goes, spelled out by the app.
- taste_lines: four lines for when she picks a vibe because the listener has been enjoying it \
lately ("Since you've been enjoying {vibe} lately, here's more."); use {vibe} where the vibe's \
spoken name goes. They lead into an episode intro.
- liked_lines: four lines for when the next episode is like one the listener liked ("You liked \
{title}, so I think you'll like this one too."); use {title} for the liked episode's title.
- resume_lines: four lines for picking a story back up where the listener left off ("Last time \
you got through part {part} of {title}. Let's keep going."); use {part} for the part they \
finished and {title} for the story's title."""

VIBES_SCHEMA = {
    "type": "object",
    "properties": {
        "vibes": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "id": {"type": "string"},
                    "name": {"type": "string"},
                    "spoken": {"type": "string"},
                    "members": {"type": "array", "items": {"type": "string"}},
                    "switch_lines": {"type": "array", "items": {"type": "string"}},
                    "ordered": {"type": "boolean"},
                },
                "required": ["id", "name", "spoken", "members", "switch_lines", "ordered"],
                "additionalProperties": False,
            },
        },
        "openers": {"type": "array", "items": {"type": "string"}},
        "part_lines": {"type": "array", "items": {"type": "string"}},
        "taste_lines": {"type": "array", "items": {"type": "string"}},
        "liked_lines": {"type": "array", "items": {"type": "string"}},
        "resume_lines": {"type": "array", "items": {"type": "string"}},
    },
    "required": ["vibes", "openers", "part_lines", "taste_lines", "liked_lines", "resume_lines"],
    "additionalProperties": False,
}


def api_client():
    """The API key comes from ANTHROPIC_API_KEY, or the same line in .env next to this script."""
    env = HERE / ".env"
    if not os.environ.get("ANTHROPIC_API_KEY") and env.exists():
        for line in env.read_text(encoding="utf-8").splitlines():
            key, _, value = line.partition("=")
            if key.strip() == "ANTHROPIC_API_KEY":
                os.environ["ANTHROPIC_API_KEY"] = value.strip().strip('"')
    return anthropic.Anthropic()


def ask_ollama(model, system, user, schema, num_ctx=16384):
    """Same question through a local model in Ollama (http://localhost:11434), its answer held
    to [schema]. Returns (result, seconds, prompt tokens, output tokens)."""
    import urllib.request
    body = json.dumps({
        "model": model,
        "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
        "format": schema,
        "stream": False,
        "think": False,
        "options": {"num_ctx": num_ctx, "temperature": 0.7},
    }).encode()
    request = urllib.request.Request("http://localhost:11434/api/chat", data=body,
                                     headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=1800) as response:
        reply = json.loads(response.read())
    return (json.loads(reply["message"]["content"]), reply.get("total_duration", 0) / 1e9,
            reply.get("prompt_eval_count", 0), reply.get("eval_count", 0))


def load_json(name):
    return json.loads((DATA / name).read_text(encoding="utf-8"))


def episode_inputs():
    """content id -> what's known about the episode, for every transcribed one."""
    catalog = {e["id"]: e for e in load_json("catalog.json")}
    soda = load_json("soda_cast.json")
    club_characters = load_json("club_characters.json")
    episodes = {}
    for path in sorted((DATA / "transcripts").glob("*.json")):
        t = json.loads(path.read_text(encoding="utf-8"))
        c = catalog.get(t["id"], {})
        story = None
        for s in soda.values():
            if s.get("name") == t["title"] and (t.get("number") is None or s.get("number") in (None, t.get("number"))):
                story = s
                break
        episodes[t["id"]] = {"transcript": t, "catalog": c, "soda": story,
                             "club_characters": club_characters.get(t["id"], [])}
    return episodes


PART_SUFFIX = re.compile(r"[,:]?\s*\(?Part \w+ of \w+\)?", re.I)


def spoken_title(title):
    """"Sergeant York, Part 3 of 4" -> "Sergeant York": the part is said separately."""
    return PART_SUFFIX.sub("", title).strip()


def episode_prompt(info):
    t, c, s = info["transcript"], info["catalog"], info["soda"] or {}
    part = PART.search(t.get("short_name") or t["title"])
    lines = [
        f"Title: {spoken_title(t['title'])}",
        f"Part: {part.group(1)} of {part.group(2)}" if part else "Part: not a multi-part story",
        f"Number: {t.get('number')}",
        f"Album: {t.get('album') or c.get('album_name')}",
        f"First aired: {s.get('date') or 'unknown'}",
        f"Club description: {c.get('description') or 'none'}",
    ]
    if s.get("story"):
        lines.append("Fan summary: " + " ".join(s["story"]))
    cast = [f"{x['name']} ({x['actor']})" if x.get("actor") else x["name"] for x in t.get("cast", [])]
    if cast:
        lines.append("Cast: " + ", ".join(cast))
    if info["club_characters"]:
        lines.append("Main characters per the Club: " + ", ".join(x["name"] for x in info["club_characters"]))
    lines.append("")
    lines.append("Transcript:")
    for u in t["utterances"]:
        lines.append(f"{u.get('name') or '?'}: {u['text']}")
    return "\n".join(lines)


def episode_request(info):
    return dict(
        model=MODEL,
        max_tokens=16000,
        system=[{"type": "text", "text": PERSONA + "\n\n" + EPISODE_INSTRUCTIONS,
                 "cache_control": {"type": "ephemeral"}}],
        messages=[{"role": "user", "content": episode_prompt(info)}],
        output_config={"effort": "medium", "format": {"type": "json_schema", "schema": EPISODE_SCHEMA}},
    )


def parse_result(message):
    if message.stop_reason == "refusal":
        raise RuntimeError("refused")
    if message.stop_reason == "max_tokens":
        raise RuntimeError("ran out of tokens")
    text = next(b.text for b in message.content if b.type == "text")
    return json.loads(text)


def tidy(result, title):
    """Fixes what smaller models get wrong despite the instructions: repeats, capitalized tags,
    and titles in quotes or with their part number in what Mabel says."""
    def unique(items):
        return list(dict.fromkeys(items))

    result["moods"] = unique(result["moods"])
    result["tags"] = unique(t.strip().lower() for t in result["tags"] if t.strip())
    result["themes"] = unique(t.strip().lower() for t in result["themes"] if t.strip())
    name = spoken_title(title)
    intros = []
    for line in result["intros"]:
        line = re.sub(re.escape(name) + PART_SUFFIX.pattern, name, line, flags=re.I)
        for q1, q2 in (("'", "'"), ('"', '"'), ("‘", "’"), ("“", "”")):
            line = line.replace(f"{q1}{name}{q2}", name)
        intros.append(re.sub(r"\s+", " ", line).strip())
    result["intros"] = intros
    return result


def save_episode(content_id, info, result):
    t = info["transcript"]
    result = tidy(result, t["title"])
    result = {"id": content_id, "title": t["title"], "short_name": t.get("short_name"),
              "number": t.get("number"), **result}
    EPISODES_OUT.mkdir(parents=True, exist_ok=True)
    (EPISODES_OUT / f"{content_id}.json").write_text(
        json.dumps(result, indent=1, ensure_ascii=False), encoding="utf-8")


def cmd_episodes(args):
    episodes = episode_inputs()
    todo = [i for i in episodes if not (EPISODES_OUT / f"{i}.json").exists()]
    if args.ids:
        todo = [i for i in args.ids if i in episodes]
    elif args.sample:
        random.seed(args.seed)
        todo = random.sample(todo, min(args.sample, len(todo)))
    print(f"{len(todo)} episodes to do")
    if not todo:
        return

    if args.ollama:
        if sys.platform == "win32":
            # Keeps the PC from sleeping until this exits (the screen can still turn off).
            import ctypes
            ctypes.windll.kernel32.SetThreadExecutionState(0x80000000 | 0x00000001)
        started = time.time()
        for n, content_id in enumerate(todo, 1):
            info = episodes[content_id]
            try:
                result, seconds, prompt_tokens, output_tokens = ask_ollama(
                    args.ollama, PERSONA + "\n\n" + EPISODE_INSTRUCTIONS, episode_prompt(info), EPISODE_SCHEMA)
                save_episode(content_id, info, result)
                print(f"[{n}/{len(todo)}] {info['transcript'].get('short_name')}: "
                      f"{seconds:.0f}s ({prompt_tokens} in, {output_tokens} out)", flush=True)
            except Exception as e:  # noqa: BLE001 - reported, and re-run picks it up
                print(f"[{n}/{len(todo)}] {content_id} failed: {e}", flush=True)
        print(f"{(time.time() - started) / len(todo):.0f}s per episode")
        return

    client = api_client()
    if args.batch:
        from anthropic.types.message_create_params import MessageCreateParamsNonStreaming
        from anthropic.types.messages.batch_create_params import Request
        batch = client.messages.batches.create(requests=[
            Request(custom_id=i, params=MessageCreateParamsNonStreaming(**episode_request(episodes[i])))
            for i in todo
        ])
        print(f"batch {batch.id}; waiting (usually under an hour)")
        (OUT / "last_batch.txt").write_text(batch.id)
        while True:
            batch = client.messages.batches.retrieve(batch.id)
            if batch.processing_status == "ended":
                break
            print(f"  {batch.request_counts.succeeded} done, {batch.request_counts.processing} left")
            time.sleep(60)
        failed = 0
        for r in client.messages.batches.results(batch.id):
            try:
                if r.result.type != "succeeded":
                    raise RuntimeError(r.result.type)
                save_episode(r.custom_id, episodes[r.custom_id], parse_result(r.result.message))
            except Exception as e:  # noqa: BLE001 - reported, and re-run picks them up
                failed += 1
                print(f"  {r.custom_id}: {e}")
        print(f"done; {failed} failed (run again to retry them)")
        return

    usage = Counter()
    for n, content_id in enumerate(todo, 1):
        info = episodes[content_id]
        try:
            message = client.messages.create(**episode_request(info))
            save_episode(content_id, info, parse_result(message))
            usage["input"] += message.usage.input_tokens
            usage["cache_read"] += message.usage.cache_read_input_tokens or 0
            usage["output"] += message.usage.output_tokens
            print(f"[{n}/{len(todo)}] {info['transcript'].get('short_name')}")
        except anthropic.RateLimitError:
            print("rate limited; waiting a minute")
            time.sleep(60)
        except (anthropic.APIStatusError, anthropic.APIConnectionError, RuntimeError) as e:
            print(f"[{n}/{len(todo)}] {content_id} failed: {e}")
    print(f"tokens: {dict(usage)}")


def cmd_vibes(args):
    results = [json.loads(p.read_text(encoding="utf-8")) for p in EPISODES_OUT.glob("*.json")]
    counts = Counter()
    for r in results:
        clean_nulls(r)
        counts.update(f"tag: {x}" for x in r["tags"])
        counts.update(f"mood: {x}" for x in r["moods"])
        if r["arc"]:
            counts[f"arc: {r['arc']}"] += 1
        if r["season"]:
            counts[f"season: {r['season']}"] += 1
    # A tag on one or two episodes can't make a run of them, and leaving them out keeps the
    # list short enough for a local model's context.
    listing = "\n".join(f"{k} ({v})" for k, v in counts.most_common() if v >= args.min_count)
    print(f"{sum(1 for v in counts.values() if v >= args.min_count)} of {len(counts)} tags")
    if args.ollama:
        import ctypes
        ctypes.windll.kernel32.SetThreadExecutionState(0x80000001)
        vibes, seconds, n_in, n_out = ask_ollama(args.ollama, PERSONA + "\n\n" + VIBE_INSTRUCTIONS,
                                                 listing, VIBES_SCHEMA, num_ctx=32768)
        print(f"{seconds:.0f}s ({n_in} in, {n_out} out)")
        (OUT / "vibes.json").write_text(json.dumps(vibes, indent=1, ensure_ascii=False), encoding="utf-8")
        print(f"{len(vibes['vibes'])} vibes")
        return
    client = api_client()
    with client.messages.stream(
        model=MODEL,
        max_tokens=64000,
        system=PERSONA + "\n\n" + VIBE_INSTRUCTIONS,
        messages=[{"role": "user", "content": listing}],
        output_config={"effort": "high", "format": {"type": "json_schema", "schema": VIBES_SCHEMA}},
    ) as stream:
        message = stream.get_final_message()
    vibes = parse_result(message)
    (OUT / "vibes.json").write_text(json.dumps(vibes, indent=1, ensure_ascii=False), encoding="utf-8")
    print(f"{len(vibes['vibes'])} vibes")


PART = re.compile(r"Part (\d+) of (\d+)", re.I)


def clean_nulls(r):
    """A local model sometimes writes "null" as text for no arc or season."""
    for key in ("arc", "season"):
        if isinstance(r.get(key), str) and r[key].strip().lower() in ("null", "none", ""):
            r[key] = None
    return r


def cmd_build(args):
    vibes = json.loads((VIBES if VIBES.exists() else OUT / "vibes.json").read_text(encoding="utf-8"))
    episodes = {}
    for p in EPISODES_OUT.glob("*.json"):
        r = clean_nulls(json.loads(p.read_text(encoding="utf-8")))
        keys = {f"tag: {x}" for x in r["tags"]} | {f"mood: {x}" for x in r["moods"]}
        if r["arc"]:
            keys.add(f"arc: {r['arc']}")
        if r["season"]:
            keys.add(f"season: {r['season']}")
        keys = {k.lower() for k in keys}
        plain = {k.split(": ", 1)[1] for k in keys}
        part = PART.search(r.get("short_name") or "")
        episodes[r["id"]] = {
            "title": r["title"],
            "number": r["number"],
            "part": [int(part.group(1)), int(part.group(2))] if part else None,
            "season": r["season"],
            "vibes": [v["id"] for v in vibes["vibes"]
                      if any(m.lower() in keys or m.lower() in plain for m in v["members"])],
        }
    out = {
        "version": 1,
        "openers": vibes["openers"],
        "part_lines": vibes["part_lines"],
        "taste_lines": vibes.get("taste_lines", []),
        "liked_lines": vibes.get("liked_lines", []),
        "resume_lines": vibes.get("resume_lines", []),
        "intro_lines": vibes.get("intro_lines", []),
        "vibes": [{k: v[k] for k in ("id", "name", "spoken", "switch_lines", "ordered")} for v in vibes["vibes"]],
        "episodes": episodes,
    }
    target = Path(args.out) if args.out else (APP_ASSET if APP_ASSET.parent.parent.exists() else OUT / "mabel.json")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(json.dumps(out, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(f"wrote {target}")
    sizes = Counter(len(e["vibes"]) for e in episodes.values())
    print(f"{len(episodes)} episodes, {len(out['vibes'])} vibes; vibes per episode: {dict(sorted(sizes.items()))}")


def cmd_show(args):
    for p in sorted(EPISODES_OUT.glob("*.json")):
        r = json.loads(p.read_text(encoding="utf-8"))
        print(f"\n{r['short_name']}")
        print(f"  {r['summary']}")
        print(f"  arc: {r['arc']}  |  setting: {r['setting']}  |  season: {r['season']}")
        print(f"  characters: {', '.join(r['main_characters'])}")
        print(f"  themes: {', '.join(r['themes'])}  |  moods: {', '.join(r['moods'])}")
        print(f"  tags: {', '.join(r['tags'])}")
        for line in r["intros"]:
            print(f"  > {line}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="cmd", required=True)
    p = sub.add_parser("episodes")
    p.add_argument("--sample", type=int, help="only this many random episodes")
    p.add_argument("--seed", type=int, default=7)
    p.add_argument("--ids", nargs="+", help="only these content ids")
    p.add_argument("--batch", action="store_true", help="use the Batch API (half price, slower)")
    p.add_argument("--ollama", metavar="MODEL", help="use this local Ollama model instead of Claude")
    p = sub.add_parser("vibes")
    p.add_argument("--ollama", metavar="MODEL", help="use this local Ollama model instead of Claude")
    p.add_argument("--min-count", type=int, default=3, help="leave out tags on fewer episodes")
    p = sub.add_parser("build")
    p.add_argument("--out", help="where to write mabel.json (default: the app's assets)")
    sub.add_parser("show")
    args = parser.parse_args()
    {"episodes": cmd_episodes, "vibes": cmd_vibes, "build": cmd_build, "show": cmd_show}[args.cmd](args)


if __name__ == "__main__":
    sys.exit(main())
