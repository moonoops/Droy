"""Build the bundled lexicon; run with jieba==0.42.1 and pypinyin==0.55.0.

These are the versions pinned by MaiBot fe88ec6b's uv.lock. Python is a
build-time data tool only; Android reads the generated gzip in JavaScript.
"""
from collections import defaultdict
from pathlib import Path
import gzip
import base64
import struct
import hashlib
import json
import jieba
import pypinyin
from pypinyin import Style, pinyin
from pypinyin.constants import PHRASES_DICT
from jieba.finalseg import start_P, trans_P, emit_P

assert jieba.__version__ == '0.42.1'
assert pypinyin.__version__ == '0.55.0'
root = Path(__file__).resolve().parent
dictionary = Path(jieba.__file__).parent / 'dict.txt'
words, char_freq, total = {}, defaultdict(int), 0
for line in dictionary.read_text(encoding='utf-8').splitlines():
    word, frequency, *_ = line.split()
    frequency = int(frequency)
    words[word] = frequency
    total += frequency
    for char in word:
        if '\u4e00' <= char <= '\u9fff':
            char_freq[char] += frequency
maximum = max(char_freq.values())
char_freq = {char: frequency / maximum * 1000 for char, frequency in char_freq.items()}
char_py, syllables, groups = {}, {}, defaultdict(list)
def syllable(value):
    if value not in syllables:
        syllables[value] = len(syllables)
    return syllables[value]
for point in range(0x4e00, 0x9fff):
    char = chr(point)
    py = syllable(pinyin(char, style=Style.TONE3)[0][0])
    char_py[char] = py
    groups[py].append(char)

phrases = {}
for word in PHRASES_DICT:
    phrases[word] = [syllable(row[0]) for row in pinyin(word, style=Style.TONE3)]
# A reverse index is equivalent to upstream's Cartesian product plus dictionary
# lookup, without enumerating millions of impossible long-word combinations.
homophones = []
ordered_words = sorted(words)
def pinyin_hash(indices):
    value = 2166136261
    for index in indices:
        value = ((value ^ index) * 16777619) & 0xffffffff
    return value
for index, word in enumerate(ordered_words):
    if len(word) > 1 and all(char in char_py for char in word):
        homophones.append((pinyin_hash(char_py[char] for char in word), index))
# Query verifies the complete pinyin sequence after binary hash lookup, so hash
# collisions cannot introduce a non-homophone. Equal hashes keep Unicode order.
homophones.sort()
def uints(values):
    return base64.b64encode(b''.join(struct.pack('<I', value) for value in values)).decode()
data = {'version': 2, 'words': ordered_words, 'wordFrequencies': uints(words[word] for word in ordered_words), 'total': total,
        'maxWordLength': max(map(len, words)), 'charFrequency': char_freq,
        'charPinyin': char_py, 'syllables': list(syllables), 'pinyinGroups': groups,
        'phrases': phrases, 'maxPhraseLength': max(map(len, phrases)),
        'wordHomophoneHashes': uints(value[0] for value in homophones),
        'wordHomophoneIndices': uints(value[1] for value in homophones),
        'hmm': {'start': start_P, 'transition': trans_P, 'emission': emit_P}}
encoded = json.dumps(data, ensure_ascii=False, separators=(',', ':')).encode()
destination = root / 'lexicon.json.gz'
with destination.open('wb') as output:
    with gzip.GzipFile(filename='', mode='wb', fileobj=output, mtime=0) as archive:
        archive.write(encoded)
metadata = {
    'upstream': {'repository': 'https://github.com/Mai-with-u/MaiBot',
                 'commit': 'fe88ec6b6053e40eaf430cb5ba7ed7e805add2cb',
                 'path': 'src/chat/utils/typo_generator.py',
                 'sourceGitBlobs': {'typo_generator.py': '5158d6db4bbc76db80c28b2ac49f03abdd8430db',
                                    'utils.py': 'ec81624c840c20cab47bad0f26edc28e3e60d625'}},
    'dependencies': {'jieba': jieba.__version__, 'pypinyin': pypinyin.__version__},
    'dictionarySha256': hashlib.sha256(dictionary.read_bytes()).hexdigest(),
    'lexiconSha256': hashlib.sha256(destination.read_bytes()).hexdigest(),
    'uncompressedBytes': len(encoded), 'compressedBytes': destination.stat().st_size,
    'wordCount': len(words), 'characterCount': len(char_py),
    'phraseCount': len(phrases), 'homophoneWordCount': len(homophones),
    'sourceHashes': {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
                     for path in sorted((root / 'upstream').glob('*.py'))}}
(root / 'provenance.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n')
print(json.dumps(metadata, ensure_ascii=False))
