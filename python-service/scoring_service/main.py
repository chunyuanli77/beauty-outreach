import re
from collections import Counter
from unicodedata import normalize

from fastapi import FastAPI
from pydantic import BaseModel, ConfigDict, Field, field_validator

from scoring_service.semantic import router as semantic_router

app = FastAPI(title="Lead Scoring Service")
app.include_router(semantic_router)
_HAN = "\u3400-\u4dbf\u4e00-\u9fff"
_MIXED_SCRIPT_SPACE = re.compile(
    rf"(?<=[a-z0-9])\s+(?=[{_HAN}])|(?<=[{_HAN}])\s+(?=[a-z0-9])"
)
_CLAUSE_BREAK = re.compile(
    r"[。！？；，、!?,;\n|]+|\.(?=\s|$)|\s+\b(?:but|however)\b\s+|但是|但(?=我|想|準備|准备|打算)"
)
_THIRD_PARTY = re.compile(
    r"(?:朋友|同事|室友|姐姐|妹妹|哥哥|弟弟|客户|客人|別人|别人|有人|她|他)"
    r"(?:问我|讓我|让我|請我|请我|希望|正在|在找|在看|在比较|在比較|在对比|在對比|準備|准备|打算|想|要|買|买|試|试|不想|不打算)"
    r"|(?:转述|转发|轉述|轉發|引用).{0,20}(?:想|买|買|试|試|对比|對比)"
    r"|(?:评论区|評論區).{0,35}(?:发言|發言|想试|想試)"
    r"|\b(?:my |a |our )?(?:friend|sister|brother|roommate|co-?worker|colleague|customer|client)"
    r"(?: of mine)?\s+(?:wants?|wanted|is looking|asked|plans?|is considering|is buying|is comparing|hopes?|said)"
    r"|\b(?:he|she|they)\s+(?:wants?|wanted|would like|asked|plans?|is looking|are looking)"
    r"|\b(?:reposting|quoting|relaying|people (?:in my chat )?are)\b"
)
_PAST_OR_HYPOTHETICAL = re.compile(
    r"(?:曾经|曾經|之前想|以前想|原本想|本来想|本來想|去年想|如果有人|假如|假設|假设|還沒決定|还没决定|尚未决定|沒決定|没决定|還在猶豫|还在犹豫|要不要(?:买|買|购买|購買|试|試))"
    r"|(?:如果|假如|假若|要是|倘若).{0,20}(?:以后|以後|将来|將來|未来|未來|哪天|有机会|有機會|收到|抽到|送我|有人|還不|还不)"
    r"|(?:也许|也許|或许|或許|说不定|說不定|可能).{0,9}(?:能|会|會|愿意|願意|用|借|试|試)"
    r"|\b(?:used to|wanted to|had planned to|hypothetically|in theory|maybe someday|haven't decided|not sure (?:if|whether)|undecided (?:about|whether)|"
    r"if (?:someone|i ever|i win|i were|i get gifted|we win|it falls)|(?:might|may) (?:eventually |someday |consider |just )?(?:buy|order|try|use|borrow|pick))\b"
)
_DENIAL = re.compile(
    r"(?:不|未|沒|没|沒有|没有|無|无|從不|从不|暫不|暂不|尚不|並不|并不)"
    r"(?:打算|考慮|考虑|準備|准备|計劃|计划|再|會|会|願意|愿意|想|希望|要|可能){0,3}"
    r"(?:去|再|要|買|买|購買|购买|購置|购置|購入|购入|入手|試用|试用|想試|想试|下單|下单|購|购)"
    r"|(?:不|沒有|没有|没|沒)(?:太|什麼|什么)?(?:感興趣|感兴趣|興趣|兴趣|意向)"
    r"|(?:试用|試用).{0,12}(?:退回|拒绝|拒絕|取消)|(?:购物车|購物車).{0,10}(?:移除|移出)"
    r"|\b(?:not|never|no|don't|do not|won't|would not|wouldn't)\s+"
    r"(?:(?:ever|longer|really|going|planning|plan|plans|look(?:ing)?|think(?:ing)?|want|wanted|consider(?:ing)?|intend(?:ing)?|interested|on|in|about|for|to)\s+){0,6}"
    r"(?:buy(?:ing)?|purchas(?:e|ing)|shop(?:ping)?|try(?:ing)?|order(?:ing)?|get(?:ting)?|use|interested)\b"
    r"|\b(?:not|never) (?:looking|shopping) for (?:one|it|another)\b"
)
_NEGATED_SELLER = re.compile(
    r"(?:不(?:做|接|提供|卖|賣|經營|经营|搞)|并非|並非|不是|不想(?:做|搞|買|买))"
    r".{0,8}(?:批发|批發|经销|經銷|代理|卖家|賣家|供应商|供應商|维修|維修)"
    r"|\b(?:not|no|never|don't|do not|won't|wouldn't)\s+"
    r"(?:(?:a|an|for|want|to|shop|buy|work|do|doing|offer|provide|sell|interested|in)\s+){0,6}"
    r"(?:wholesale|resale|reseller|seller|supplier|repair)\b"
)
_REFERS_TO_DEVICE = re.compile(
    r"(?:新款|旧款|舊款|这一款|這一款|这款|這款|哪一款|哪款|哪台|一款|这台|這台|一台|那台|那款|它|其|下单|下單|"
    r"入手|试用|試用|实测|實測|体验|體驗|购物车|購物車|替换款|替換款|家用款|容量|档位|檔位|"
    r"对比(?:温感|保修|参数|價格|价格)|比較(?:溫感|保固|參數|價格))"
    r"|\b(?:it|one|ones|whichever|this model|that model|these models|which model|the same model|"
    r"strap widths?|(?:my|the) (?:shopping )?(?:cart|basket|order))\b"
)
_OTHER_OBJECT = re.compile(
    r"^.{1,45}(?:\b(?:for|to|about)\b|(?:给|給|为了|為了|用于|用於|有关|有關|记录|記錄|拍摄|拍攝|介绍|介紹))"
)
_INFORMATIONAL = re.compile(r"(?:我的工作|只分享|只做(?:内容|內容)|只负责|只負責)|\b(?:for my job|my job is|for a client)\b")
_QUOTED = re.compile(r'「[^」]*」|『[^』]*』|“[^”]*”|"[^"]*"')
_DEVICE_FAMILIES = (
    ({"美容仪", "美容儀", "beauty device"}, re.compile(
        r"(?:家用)?(?:嫩肤|嫩膚|紧肤|緊膚|紧致|緊緻|提拉(?:紧致|緊緻)?|洗脸|洗臉|洁面|潔面|毛孔清洁|毛孔清潔|"
        r"面部按摩|臉部按摩|脸部按摩|面部冷敷|臉部冷敷|脸部冷敷|面部护理|臉部護理|脸部护理|"
        r"离子导入|離子導入|超声波导入|超聲波導入|超声洁面|超聲潔面|无针导入|無針導入|磁热导入|磁熱導入|"
        r"面部导入|臉部導入|美容)[仪儀棒刷器铲鏟机機]"
        r"|\b(?:(?:at[- ]home |home |electric(?:al)? |sonic |ultrasonic )?(?:facial |face |skin |skincare )"
        r"(?:toning |firming |tightening |cleansing |cleaning |massag(?:e|ing) |ice |pore )"
        r"(?:devices?|gizmos?|gadgets?|equipment|tools?|machines?|wands?|brush(?:es)?|scrubbers?|cleansers?|rollers?|"
        r"massagers?)|(?:face|facial) massagers?|skincare (?:devices?|gadgets?|tools?)|"
        r"(?:electric(?:al)? |sonic |ultrasonic )(?:(?:facial|face|skin) (?:brush(?:es)?|scrubbers?|cleansers?|wands?)|"
        r"pore cleansers?))\b"
    )),
    ({"led面罩", "led face mask", "红光面罩"}, re.compile(
        r"(?:led|红光|紅光|蓝光|藍光|光疗|光療|光子)(?:红光|紅光|光疗|光療)?"
        r"(?:护肤|護膚|美容|面部|脸部|臉部)?(?:面罩|面具|护肤仪|護膚儀)"
        r"|(?:面部|脸部|臉部)(?:红光|紅光|光疗|光療)(?:仪|儀|设备|設備)"
        r"|\b(?:led[- ](?:light[- ])?(?:(?:face|facial|skincare|treatment)[- ])?(?:masks?|facial shields?)|"
        r"facial led masks?|(?:red[- ]light|blue[- ]light|infrared|light therapy|phototherapy|collagen light)"
        r" (?:(?:face|facial|skincare|acne) )?(?:masks?|face panels?)|"
        r"(?:face|facial) (?:light therapy|phototherapy|infrared therapy) (?:tools?|devices?|panels?|gear|masks?))\b"
    )),
    ({"微电流仪", "微电流面部仪", "microcurrent device"}, re.compile(
        r"(?:面部)?(?:微电流|微電流)(?:面部|提拉|紧肤|緊膚|美容)?(?:仪|儀|棒|器)"
        r"|\b(?:micro[- ]?current|galvanic|facial ems) (?:facial |face |skin |firming |toning )?"
        r"(?:facial |face |skin )?(?:devices?|wands?|tools?|machines?)\b"
    )),
    ({"射频仪", "家用射频", "rf facial device"}, re.compile(
        r"(?:家用)?(?:射频|射頻)(?:紧肤|緊膚|美容|提拉)?(?:仪|儀|棒|器)"
        r"|\b(?:at[- ]home (?:rf|radio[ -]?frequency) tools?|(?:rf|radio[ -]?frequency) "
        r"(?:skin|face|facial|skincare) (?:tightening |firming |toning )?(?:devices?|wands?|tools?|machines?))\b"
    )),
)
_PURCHASE_INTENT = re.compile(
    r"(?:想|正|正在|准备|準備|打算|计划|計劃|預計|等.{0,14}就|這周|这周|本周|這個月|这个月|月底前|要|决定|決定|"
    r"已经|已經|已|考慮|考虑|开始|開始)(?:先|再|自己(?:出钱|出錢)?|親自|亲自|立刻|给自己|給自己|在家)?"
    r"(?:想買|想买|買|买|購買|购买|購入|购入|购置|購置|借(?:用|来|來)?|租|實測|实测|試(?:戴|用|試)?|试(?:戴|用|试)?|"
    r"体验|體驗|入手|下单|下單|訂|订|換|换|選|选|挑|找|比较|比較|对比|對比|付款|预订|預訂)"
    r"|(?:预约试戴|預約試戴|约了.{0,18}试用|約了.{0,18}試用|求推荐|求推薦|请推荐|請推薦|"
    r"(?:已加|加入)购物车|(?:已加|加入)購物車|(?<!早)就(?:买|買|挑|选|選|付款|下单|下單)|"
    r"想看看.{0,20}(?:准备借|準備借)|(?:想在家用|开始找|開始找)|找.{0,24}(?:试戴|試戴)|"
    r"(?:给|給|帮|幫).{1,5}(?:挑|选|選).{1,20}(?:礼物|禮物)|(?:约好|約好)借|"
    r"给自己挑|給自己挑|选有.{0,10}的那款|選有.{0,10}的那款)"
    r"|\b(?:(?:i(?:'m| am| will|'ll| would|'d)? |we(?:'re| are| will|'ll)? )?"
    r"(?:want |would like |'d like |plan(?:ning)? |ready |intend(?:ing)? |need |hop(?:e|ing) )to "
    r"(?:buy|purchase|try|test|rent|borrow|order|get|replace|pick|choose)|(?:i'm |i am |i'll |i will |i'd |we're |we are )"
    r"(?:like to |looking to |going to )?(?:buy(?:ing)?|purchas(?:e|ing)|rent(?:ing)?|borrow(?:ing)?|"
    r"order(?:ing)?|get(?:ting)? another|deciding between|testing)|"
    r"shopping for|saving (?:for|up to buy)|currently pricing|trying to (?:choose|pick)|"
    r"i (?:booked|scheduled) (?:a )?(?:trial|demo)|i (?:just )?put|placing (?:the|an) order|"
    r"preorder|deposit|(?:in|into) (?:my|the) (?:shopping )?(?:cart|basket)|"
    r"choosing an?|i'm after|my goal is to pick|i can try it|i'd like a)\b"
)
_EXPLICIT_PERSON = re.compile(
    r"(?:我(?:想|正|準備|准备|打算|计划|計劃|已经|已經|已|要)|我自己|给自己|給自己|自己用|在家用)"
    r"|\b(?:i|i'm|i'd|i'll|my(?:self)?|for myself|for my own)\b"
)
_COMMERCIAL_OR_OTHER_USE = re.compile(
    r"(?:厂家|廠家|代工|贴牌|貼牌|经销商|經銷商|欢迎(?:客户|顾客|到店)|歡迎(?:客戶|顧客|到店)|"
    r"店铺客服|店鋪客服|门店(?:供应|营业窗口)|門店(?:供應|營業窗口)|(?:开始|開始|主营|主營)代理|"
    r"批售|商务接单|商務接單|广告投放|廣告投放|售后车间|售後車間|"
    r"舞台(?:道具|特效)|(?:互动|互動|拍摄|拍攝|主持|当|當|作)道具|头盔灯|頭盔燈|灯光师|燈光師|"
    r"(?:和|与|與)(?:个人|個人)?(?:护肤|護膚|面部护理|面部護理)(?:无关|無關|没关系|沒關係)|"
    r"(?:不作|不做|不用来|不用來|并非用于|並非用於)(?:皮肤|皮膚|美容|护肤|護膚)(?:用途|使用|护理|護理|(?=[\s。，,.；;!?]|$))|"
    r"无线电课|無線電課|模型电路|模型電路|博物馆展品|博物館展品|课堂测波|課堂測波|"
    r"用于.{0,8}(?:教学|教學|工程|组装|組裝)|(?:公司|工作室)(?:采购|採購|出租)|"
    r"取消.{0,12}(?:预约|預約)|(?:移除|移出).{0,12}(?:购物车|購物車))"
    r"|\b(?:buy from our|our (?:web )?(?:shop|store|storefront|factory|lab|sales team)|"
    r"distributor|reseller|private[- ]label|in bulk|trade orders|business pricing|corporate contract|floor models?|ask (?:a |our )?staff member|"
    r"pricing sheets|replacement parts line|book a repair|paid reviews|promotional demos|"
    r"(?:costume|photo) props?|for (?:a |an )?.{0,20}costume|prop projects?|video sponsor|science[- ]fiction set|circuit[- ]board|"
    r"not for (?:use at home|skin|skincare)|no use on skin|"
    r"for (?:growing|our (?:customers|teaching lab)|shipments|your clinic|robot measurements|watch movements|air filtration|checking audio))\b"
)
_PAST_REFERENCE = re.compile(
    r"(?:原计划|原計劃|旧相册|舊相冊|只在归档|只在歸檔|看过|看過|朋友的.{0,8}(?:测评|測評|評測|评测)|"
    r"曾经|曾經|当年|當年|以前|几年前|幾年前|早就买好|早就買好)"
    r"|\b(?:years ago|last year's|nostalgia|archived|reproducing the manual)\b"
)
_WITHDRAWAL = re.compile(
    r"(?:现在|現在|后来|後來|最后|最後|最终|最終|已经|已經)?(?:决定|決定)(?:不要|不用|不再买|不再買|不采购|不採購)"
    r"|(?:反悔|拒收|从未打算用|從未打算用|不会采购|不會採購)"
    r"|\b(?:changed my mind|decided against|no longer (?:need|want)|ruled (?:it|them) out|not replacing it)\b"
)
_COMPARISON_CONTEXT = re.compile(r"(?:价格|價格|档位|檔位|保修|保固|预算|預算|新款|旧款|舊款|窄版|小号|小號|"
                                 r"替换款|替換款|体验|體驗|实测|實測|(?:預計|预计)(?:買|买)來當.{1,5}(?:禮物|礼物))"
                                 r"|\b(?:widths?|fit|replacement|rent|borrow|warranty|price|whichever|my order)\b")
_STANDARD_ACTION_TERMS = frozenset({
    "购买", "想买", "想试", "试用", "想了解", "求推荐", "考虑入", "打算入手", "准备入手", "准备选",
    "在找", "預計買", "对比", "比较", "值得买吗", "下单", "购物车", "want to try", "want to buy",
    "before buying", "looking to buy", "looking for", "shopping for", "considering", "comparing",
    "thinking of buying", "interested in", "would like to try", "would like to test", "i'd like to test",
    "worth buying", "planning to buy", "saving up to buy", "trying to decide", "choose one",
})
_DIRECT_ACTION = re.compile(
    r"(?:想買|想买|想購|想购|想試|想试|打算|準備|准备|預計|预计|计划|計劃|考虑入|考慮入|求推薦|求推荐|請推薦|请推荐|"
    r"先(?:借|試|试|體驗|体验)|(?:已|已经|已經)(?:预订|預訂|预约|預約|下单|下單|加购物车|加購物車)|"
    r"就(?:買|买|付款|下单|下單)|週[一二三四五六日]下[单單]|本周下[单單]|"
    r"(?:这个月|這個月|预算|預算|保修|保固).{0,12}(?:买|買|挑|选|選|订|訂|试|試))"
    r"|\b(?:want to (?:buy|try|purchase)|looking to buy|shopping for|planning to buy|saving up to buy|"
    r"thinking of buying|(?:would like to|i'd like to) (?:test|try)|trying to (?:choose|decide)|"
    r"booked a trial|placing (?:an|the) order|preorder|(?:my|the) (?:shopping )?(?:cart|basket)|"
    r"(?:i|we)(?:'m| am|'re| are| will|'ll)? (?:want|wants|need|plan|would|intend|buying|"
    r"looking|trying|comparing|considering|choose|pick|booked)|for myself|for my own)\b"
)
_DELIBERATE_SHOPPING = re.compile(
    r"(?:预算|預算|保修|保固|保养费|保養費|售后|售後|电压|電壓|下单|下單|购物车|購物車|"
    r"自己用|自己出钱|自己出錢|哪一款|哪款|功率要求|温感|溫感|比价|比價|折扣|退换货|退換貨)"
    r"|\b(?:my budget|my own|before i choose|which model|price match|price limit|warranty terms?|"
    r"return policy|battery life|strap widths?|before i order|before i buy|"
    r"rental option|compare prices|narrowed (?:it|them) down|personal use|replace the one|within \$)\b"
)


def normalized(value: str) -> str:
    return normalize("NFKC", value).casefold().strip()


def matching_text(value: str) -> str:
    return _MIXED_SCRIPT_SPACE.sub("", " ".join(value.replace("’", "'").split()))


def matching_terms(terms: list[str], bio: str) -> dict[str, str]:
    matches = {}
    for term in terms:
        key = matching_text(term)
        if key in bio:
            matches.setdefault(key, term)
    return matches


def contextual_matches(bio: str, core: list[str], intent: list[str]):
    core_terms = {matching_text(term) for term in core}
    device_matches = matching_terms(core, bio)
    for aliases, pattern in _DEVICE_FAMILIES:
        if core_terms & aliases:
            for match in pattern.finditer(bio):
                if not any(term in match[0] or match[0] in term for term in device_matches):
                    device_matches[match[0]] = match[0]
    intent_matches = matching_terms(intent, bio)
    # Recognize ordinary grammatical variants only when a standard buying/trying category is enabled.
    if core_terms and set(intent) & {"购买", "想买", "想试", "试用", "want to buy", "want to try"}:
        for match in _PURCHASE_INTENT.finditer(bio):
            if not any(term in match[0] or match[0] in term for term in intent_matches):
                intent_matches[match[0]] = match[0]
    return device_matches, intent_matches


def occurrence_starts(text: str, term: str):
    start = text.find(term)
    while start != -1:
        yield start
        start = text.find(term, start + 1)


def scoped_intent(bio: str, core_terms: dict[str, str], intent_terms: dict[str, str], exclusions: dict[str, str],
                  configured_intent: list[str]):
    # Sentence and clause boundaries stay intact while normalizing each clause for matching.
    clauses = [matching_text(clause) for clause in _CLAUSE_BREAK.split(bio) if clause.strip()]
    products = [matching_terms(list(core_terms), clause) for clause in clauses]
    effective_exclusions = Counter()
    negated_seller_exclusions = set()
    intent_hits = set()
    review = set()
    previous_product_denied = Counter()
    standard_actions = bool(_STANDARD_ACTION_TERMS.intersection(configured_intent))

    for index, clause in enumerate(clauses):
        clause_core = products[index]
        previous_core = index > 0 and products[index - 1]
        nearby_reference = previous_core and (_REFERS_TO_DEVICE.search(clause) or _COMPARISON_CONTEXT.search(clause))
        reported = _THIRD_PARTY.search(clause)
        hypothetical = _PAST_OR_HYPOTHETICAL.search(clause) or _PAST_REFERENCE.search(clause)
        denial = _DENIAL.search(clause)
        negated_seller = _NEGATED_SELLER.search(clause)
        informational = _INFORMATIONAL.search(clause) or _COMMERCIAL_OR_OTHER_USE.search(clause)
        quoted = list(_QUOTED.finditer(clause))
        clause_intents = set()
        clause_exclusions = [(phrase, start) for phrase in exclusions for start in occurrence_starts(clause, phrase)]
        for phrase, start in clause_exclusions:
            if negated_seller and start < negated_seller.end() and start + len(phrase) > negated_seller.start():
                negated_seller_exclusions.add(phrase)
            else:
                effective_exclusions[phrase] += 1

        for phrase in intent_terms:
            for start in occurrence_starts(clause, phrase):
                end = start + len(phrase)
                if any(left <= start and end <= left + len(exclusion) for exclusion, left in clause_exclusions):
                    continue
                if reported or any(span.start() <= start < span.end() for span in quoted):
                    review.add("第三方或转述")
                elif hypothetical:
                    review.add("既往或假设意向")
                elif denial and not negated_seller:
                    review.add("否定或尚未决定")
                elif informational or not clause_core and not nearby_reference:
                    if core_terms:
                        review.add("未绑定美容设备")
                elif clause_core and not product_near_intent(clause, clause_core, start, end):
                    review.add("未绑定美容设备")
                elif standard_actions and phrase in _STANDARD_ACTION_TERMS and not (
                    _DIRECT_ACTION.search(clause) or _DELIBERATE_SHOPPING.search(clause)
                ):
                    review.add("意向证据不足")
                else:
                    clause_intents.add(phrase)

        # A generic new model may follow a rejected old model without repeating the product name.
        if previous_product_denied and clause_intents and (
            (nearby_reference and re.search(r"(?:新款|新型号|小号家用款|小號家用款|\bnew(?:er)? model\b)", clause))
            or (clause_core and _EXPLICIT_PERSON.search(clause))
        ):
            effective_exclusions -= previous_product_denied
        if not clause_intents and clause_core:
            if reported:
                review.add("第三方或转述")
            if hypothetical:
                review.add("既往或假设意向")
        intent_hits.update(clause_intents)
        previous_product_denied = Counter(
            phrase for phrase, start in clause_exclusions if start <= denial.end()
        ) if denial and (clause_core or reported) else Counter()

    if not intent_hits:
        effective_exclusions.update(negated_seller_exclusions)
    if not intent_hits and core_terms and _DENIAL.search(bio):
        review.add("否定或尚未决定")
    if intent_hits and core_terms and _WITHDRAWAL.search(bio):
        review.add("否定或尚未决定")
        intent_hits.clear()
    if intent_terms and core_terms and _COMMERCIAL_OR_OTHER_USE.search(bio):
        review.add("未绑定美容设备")
        intent_hits.clear()
    return intent_hits, effective_exclusions, review


def product_near_intent(clause: str, products: dict[str, str], start: int, end: int) -> bool:
    for product in products:
        for product_start in occurrence_starts(clause, product):
            product_end = product_start + len(product)
            if product_start >= end:
                gap = clause[end:product_start]
                if len(gap) <= 36 and not _OTHER_OBJECT.search(gap):
                    return True
            elif start - product_end <= 36:
                return True
    return False


class ScoreRules(BaseModel):
    model_config = ConfigDict(strict=True)

    version: int = Field(ge=1)
    core: list[str]
    intent: list[str]
    exclude: list[str]
    regions: list[str]
    languages: list[str]
    threshold: int = Field(ge=0, le=100)

    @field_validator("core", "intent", "exclude", "regions", "languages")
    @classmethod
    def clean_terms(cls, values: list[str]) -> list[str]:
        terms = dict.fromkeys(normalized(value) for value in values)
        if "" in terms:
            raise ValueError("rule terms must not be blank")
        return list(terms)


class ScoreRequest(BaseModel):
    model_config = ConfigDict(strict=True)

    bio: str
    region: str | None = None
    language: str | None = None
    rules: ScoreRules


class ScoreResult(BaseModel):
    score: int
    hits: list[str]
    eligible: bool
    rule_version: int


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/score")
def score_lead(request: ScoreRequest) -> ScoreResult:
    rules = request.rules
    raw_bio = normalized(request.bio)
    bio = matching_text(raw_bio)
    region = normalized(request.region or "")
    language = normalized(request.language or "")

    core_matches, intent_matches = contextual_matches(bio, rules.core, rules.intent)
    exclusions = matching_terms(rules.exclude, bio)
    intents, excluded, review = scoped_intent(raw_bio, core_matches, intent_matches, exclusions, rules.intent)
    intent_hits = [term for phrase, term in intent_matches.items() if phrase in intents]
    exclude_hits = [term for phrase, term in exclusions.items() if phrase in excluded]
    region_matches = region in rules.regions
    language_matches = language in rules.languages
    preference_matches = (
        bool(core_matches and intent_hits and (rules.regions or rules.languages))
        and (not rules.regions or region_matches)
        and (not rules.languages or language_matches)
    )

    score = (
        (60 if core_matches else 0)
        + (25 if intent_hits else 0)
        + (15 if preference_matches else 0)
        - (40 if exclude_hits else 0)
    )
    score = max(0, min(100, score))
    hits = [
        f"{category}:{term}"
        for category, terms in (
            ("core", core_matches.values()),
            ("intent", intent_hits),
            ("exclude", exclude_hits),
        )
        for term in terms
    ]
    if preference_matches:
        if region_matches:
            hits.append(f"region:{region}")
        if language_matches:
            hits.append(f"language:{language}")
    if not intent_hits:
        hits.extend(f"review:{reason}" for reason in sorted(review))

    return ScoreResult(
        score=score,
        hits=hits,
        eligible=score >= rules.threshold,
        rule_version=rules.version,
    )
