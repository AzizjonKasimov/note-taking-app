# /// script
# requires-python = ">=3.11"
# dependencies = ["sherpa-onnx==2.0.3", "numpy", "soundfile", "scipy"]
# ///
"""Scores on-device speech models on your own recordings, so the app's model is picked on your voice
rather than on a leaderboard.

    uv run tools/speech-bench/speech_bench.py run <model folder> --items <items.json> [--hotwords <names.txt>]
    uv run tools/speech-bench/speech_bench.py export --items <items.json> --out <folder>
    uv run tools/speech-bench/speech_bench.py score --items <items.json> <result.json> [<result.json> ...]

items.json is a list of {"id", "file", "said"} ("line" if there is no "said"), with "file" a WAV of any
rate, relative to the items file. When items carry a "kind", only "take" items are used; a "mic" splits
the scores by microphone.

run transcribes every item with a sherpa-onnx transducer model folder (encoder, decoder, and joiner .onnx
files and tokens.txt) on the CPU, the way the app runs it on the phone, and writes out/<label>.json beside
this script: each item's text and the seconds it took. With --hotwords (one name per line, # for
comments) it decodes with modified beam search and boosts those names, which needs a bpe.vocab in the
model folder.

export writes the items as 16 kHz mono WAVs named by id, the form the app records, to push to a phone
for the app's TranscriberDeviceTest, whose bench/result.json score reads like any other result.

score prints the word error rate of each result file, overall and per microphone, and the items it got
wrong. It also reads result files that list words ({"items": {id: {"words": [{"w": ...}]}}}).
"""
import argparse
import json
import re
import sys
import time
from math import gcd
from pathlib import Path

HERE = Path(__file__).resolve().parent
RATE = 16000
ONES = ("zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen "
        "seventeen eighteen nineteen").split()
TENS = "_ _ twenty thirty forty fifty sixty seventy eighty ninety".split()


def spelled(n):
    """A whole number under a million in words: 1500 as [one, thousand, five, hundred]."""
    if n < 20:
        return [ONES[n]]
    if n < 100:
        return [TENS[n // 10]] + ([ONES[n % 10]] if n % 10 else [])
    if n < 1000:
        return [ONES[n // 100], "hundred"] + (spelled(n % 100) if n % 100 else [])
    return spelled(n // 1000) + ["thousand"] + (spelled(n % 1000) if n % 1000 else [])


def words(text):
    """Lowercase words with only letters, digits, and apostrophes; hyphens split words, numbers are spelled."""
    text = re.sub(r"(?<=\d),(?=\d{3})", "", text.lower().replace("’", "'")).replace("-", " ")
    out = []
    for raw in text.split():
        w = re.sub(r"[^a-z0-9']", "", raw).strip("'")
        if w.isdigit() and int(w) < 1_000_000:
            out += spelled(int(w))
        elif w:
            out.append(w)
    return out


def edits(want, got):
    """Edit distance between two word lists: words left out, added, or changed."""
    d = list(range(len(got) + 1))
    for i, w in enumerate(want, 1):
        prev, d[0] = d[0], i
        for j, g in enumerate(got, 1):
            prev, d[j] = d[j], min(d[j] + 1, d[j - 1] + 1, prev + (w != g))
    return d[-1]


def load_items(path):
    items = json.loads(Path(path).read_text(encoding="utf-8"))
    if any("kind" in i for i in items):
        items = [i for i in items if i.get("kind") == "take"]
    return items


def audio(path):
    """The file as 16 kHz mono float32."""
    import numpy as np
    import soundfile
    from scipy.signal import resample_poly

    x, rate = soundfile.read(path, dtype="float32", always_2d=True)
    x = x.mean(axis=1)
    if rate != RATE:
        g = gcd(rate, RATE)
        x = resample_poly(x, RATE // g, rate // g).astype(np.float32)
    return x


def run(args):
    import sherpa_onnx

    folder = Path(args.model)
    found = {kind: sorted(folder.glob(f"{kind}*.onnx")) for kind in ("encoder", "decoder", "joiner")}
    if not all(found.values()):
        sys.exit(f"{folder} needs encoder, decoder, and joiner .onnx files")
    options = dict(encoder=str(found["encoder"][0]), decoder=str(found["decoder"][0]), joiner=str(found["joiner"][0]),
                   tokens=str(folder / "tokens.txt"), num_threads=args.threads, model_type="nemo_transducer")
    if args.hotwords:
        vocab = folder / "bpe.vocab"
        if not vocab.exists():
            sys.exit(f"--hotwords needs {vocab}")
        names = [n.strip() for n in Path(args.hotwords).read_text(encoding="utf-8").splitlines()]
        names = [n for n in names if n and not n.startswith("#")]
        hot = HERE / "out" / f"{args.label}.hotwords.txt"
        hot.parent.mkdir(exist_ok=True)
        hot.write_text("\n".join(f"{n} :{args.score}" for n in names) + "\n", encoding="utf-8")
        options.update(decoding_method="modified_beam_search", hotwords_file=str(hot), hotwords_score=args.score,
                       modeling_unit="bpe", bpe_vocab=str(vocab))
    elif args.beam:
        options.update(decoding_method="modified_beam_search")
    started = time.time()
    recognizer = sherpa_onnx.OfflineRecognizer.from_transducer(**options)
    print(f"loaded {folder.name} in {time.time() - started:.1f} s")
    root = Path(args.items).resolve().parent
    results, total_audio, total_time = {}, 0.0, 0.0
    for n, item in enumerate(load_items(args.items), 1):
        x = audio(root / item["file"])
        started = time.time()
        stream = recognizer.create_stream()
        stream.accept_waveform(RATE, x)
        recognizer.decode_stream(stream)
        took = time.time() - started
        results[item["id"]] = {"text": stream.result.text.strip(), "seconds": round(took, 3),
                               "audio_seconds": round(len(x) / RATE, 2)}
        total_audio += len(x) / RATE
        total_time += took
        if n % 25 == 0:
            print(f"{n} items, {total_time / total_audio:.3f} s per second of audio")
    out = HERE / "out" / f"{args.label}.json"
    out.parent.mkdir(exist_ok=True)
    out.write_text(json.dumps({"engine": args.label, "model": folder.name, "options": {
        k: v for k, v in options.items() if k in ("decoding_method", "hotwords_score", "num_threads")},
        "items": results}, indent=1, ensure_ascii=False), encoding="utf-8")
    print(f"{len(results)} items in {total_time:.0f} s ({total_time / total_audio:.3f} s per second of audio) -> {out}")


def export(args):
    """Writes each item as a 16 kHz mono 16-bit WAV named by its id, the form the app records, for a device run."""
    import numpy as np
    import soundfile

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    root = Path(args.items).resolve().parent
    items = load_items(args.items)
    for item in items:
        x = np.clip(audio(root / item["file"]), -1.0, 1.0)
        soundfile.write(out / f"{item['id']}.wav", x, RATE, subtype="PCM_16")
    print(f"{len(items)} recordings in {out}")


def heard(entry):
    if "text" in entry:
        return entry["text"]
    return " ".join(w["w"] for w in entry.get("words", []))


def score(args):
    items = load_items(args.items)
    for path in args.results:
        data = json.loads(Path(path).read_text(encoding="utf-8"))
        got = data["items"]
        totals, wrong = {}, []
        for item in items:
            if item["id"] not in got:
                continue
            want = words(item.get("said") or item["line"])
            hyp = words(heard(got[item["id"]]))
            e = edits(want, hyp)
            for key in ("all", item.get("mic", "all")):
                t = totals.setdefault(key, [0, 0, 0])
                t[0] += e
                t[1] += len(want)
                t[2] += 1
            if e:
                wrong.append((e, item["id"], " ".join(want), " ".join(hyp)))
        name = data.get("engine", Path(path).stem)
        parts = [f"{key} {t[0] / t[1]:.1%} ({t[0]}/{t[1]} words, {t[2]} items)" for key, t in totals.items()]
        print(f"\n{name}: " + "; ".join(parts) + f"; {len(wrong)} items with an error")
        for e, id_, want, hyp in sorted(wrong, reverse=True)[:args.show]:
            print(f"  {e}  {id_}\n     said: {want}\n    heard: {hyp}")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="step", required=True)
    p = sub.add_parser("run", help="transcribe every item with one model")
    p.add_argument("model", help="a sherpa-onnx transducer model folder")
    p.add_argument("--items", required=True)
    p.add_argument("--hotwords", help="names to boost, one per line")
    p.add_argument("--score", type=float, default=1.5, help="how much to boost each name (default 1.5)")
    p.add_argument("--beam", action="store_true", help="modified beam search without names")
    p.add_argument("--threads", type=int, default=4, help="CPU threads (default 4, as on a phone)")
    p.add_argument("--label", help="the result's name (default: the model folder's name)")
    p = sub.add_parser("export", help="write the items as 16 kHz WAVs for a run on a phone")
    p.add_argument("--items", required=True)
    p.add_argument("--out", required=True, help="a folder outside any git repo")
    p = sub.add_parser("score", help="word error rates of result files")
    p.add_argument("--items", required=True)
    p.add_argument("results", nargs="+")
    p.add_argument("--show", type=int, default=15, help="how many wrong items to print per result")
    args = parser.parse_args()
    if args.step == "run":
        args.label = args.label or Path(args.model).name + ("-names" if args.hotwords else "-beam" if args.beam else "")
        run(args)
    elif args.step == "export":
        export(args)
    else:
        score(args)


if __name__ == "__main__":
    main()
