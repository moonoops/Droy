"""Generate regression cases using the unchanged upstream Python class."""
from pathlib import Path
import importlib.util
import json
import random
import sys
import types
import ast
from dataclasses import dataclass, asdict
root = Path(__file__).resolve().parent

logger = types.ModuleType('src.common.logger')
logger.get_logger = lambda _: types.SimpleNamespace(debug=lambda _: None)
sys.modules['src.common.logger'] = logger
spec = importlib.util.spec_from_file_location('upstream_typo', root / 'upstream/typo_generator.py')
upstream = importlib.util.module_from_spec(spec)
spec.loader.exec_module(upstream)
data = json.loads(__import__('gzip').decompress((root / 'lexicon.json.gz').read_bytes()))
# Avoid creating the upstream cache file outside the fixture directory.
upstream.ChineseTypoGenerator._load_or_create_char_frequency = staticmethod(lambda: data['charFrequency'])

def seeded(seed):
    state = seed
    def next_random():
        nonlocal state
        state = (state * 1664525 + 1013904223) & 0xffffffff
        return state / 4294967296
    return next_random

sentences = ['你好啊，我今天好开心', '这个事情有点意思', '我去吃饭了，等会回来',
             '你说的没错，我也是这样想的', '他应该已经到北京了', '明天再说吧，现在困了',
             '买这个东西是不是不太好', '我知道了，不过为什么呢', '我喜欢蓝色，也喜欢晴天',
             '好，知道啦']
cases = []
for seed in range(80):
    sentence = sentences[seed % len(sentences)]
    rng = seeded(seed)
    upstream.random.random = rng
    upstream.random.choice = lambda values: values[int(rng() * len(values))]
    # High rates exercise rare branches without needing thousands of prose samples.
    settings = dict(error_rate=.4, min_freq=9, tone_error_rate=.3, word_replace_rate=.3)
    generator = upstream.ChineseTypoGenerator(**settings)
    text, correction = generator.create_typo_sentence(sentence)
    cases.append(dict(seed=seed, input=sentence, text=text, correction=correction))

segments = ['南京市长江大桥', '我来到北京清华大学', '他来到了网易杭研大厦',
            '小明硕士毕业于中国科学院计算所', '这是一个新奇的咔咔麦麦词汇',
            '乒乓球拍卖完了', '研究生命起源', '你今天吃什么呀', '不包容但善良，偶尔吐槽',
            '我想试试Droy v0.0.3，哈哈', '状态：API_key_23 123.45% https://example.com']
words = ['银行', '长大', '重庆', '音乐', '快乐', '你好', '事情', '知道', '北京', '晴天', '打球', '马上']
generator = upstream.ChineseTypoGenerator(min_freq=9)
output = dict(source='MaiBot fe88ec6b: unchanged typo_generator.py, jieba 0.42.1, pypinyin 0.55.0',
              cases=cases,
              segmentation=[dict(input=text,words=generator._segment_sentence(text)) for text in segments],
              wordCandidates=[dict(word=word,pinyin=generator._get_word_pinyin(word),
                                   candidates=generator._get_word_homophones(word)) for word in words])
@dataclass
class ProcessedResponseSegment:
    text: str
    quote_previous: bool = False
    separator: str = ''
    is_correction: bool = False
tree = ast.parse((root / 'upstream/utils.py').read_text())
function = next(item for item in tree.body if isinstance(item, ast.FunctionDef) and item.name == '_apply_response_post_processing')
settings = types.SimpleNamespace(enable=True,error_rate=.4,min_freq=9,tone_error_rate=.3,
                                 word_replace_rate=.3,enable_correction_quote=True,correction_quote_probability=1.0)
scope = dict(ProcessedResponseSegment=ProcessedResponseSegment,ChineseTypoGenerator=upstream.ChineseTypoGenerator,
             global_config=types.SimpleNamespace(chinese_typo=settings),random=random)
exec(compile(ast.Module(body=[function],type_ignores=[]),'<upstream-utils>','exec'),scope)
output['postProcessing'] = []
for seed in range(40):
    rng=seeded(seed)
    random.random=rng
    random.choice=lambda values:values[int(rng()*len(values))]
    parts=[(sentences[seed%len(sentences)],'，'),(sentences[(seed+1)%len(sentences)],'')]
    output['postProcessing'].append(dict(seed=seed,input=parts,
        output=[asdict(segment) for segment in scope['_apply_response_post_processing'](parts,True)]))
(root / 'reference-cases.json').write_text(json.dumps(output, ensure_ascii=False, indent=2)+'\n')
print('Wrote', len(cases), 'upstream random-stream cases')
