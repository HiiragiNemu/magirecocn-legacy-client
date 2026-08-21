"""守卫脚本共用的小工具。

抽出来是因为「去掉注释再判断」这件事不止一个守卫需要，而它一旦各写一份就会
各自漂移。第一个踩坑的是 check-download-ui-contract.py（见 code_lines 的注释），
第二个是 check-dir-fsync-contract.py——它要判断「没有任何地方用 RandomAccessFile
打开目录」，结果被自己那句「不要改回 RandomAccessFile」的警告注释顶了红灯。
"""


def code_lines(src):
    """去掉注释后的非空代码行。

    本文件的判据是「源码里写没写某段字」，而这个仓库的注释写得比代码还长，
    里头经常**原样引用**被禁掉的写法（例如「⚠ 绝不能设 setTextIsSelectable(true)」）。
    拿整份文本做 `not in` 判断的话，注释会替代码顶罪：把危险写法解释清楚的那条注释
    反而让守卫红灯。凡是「某写法必须不存在」的判据，都过这一层。
    """
    out, in_block = [], False
    for raw in src.splitlines():
        line = raw
        if in_block:
            end = line.find("*/")
            if end < 0:
                continue
            line, in_block = line[end + 2:], False
        while True:
            start = line.find("/*")
            if start < 0:
                break
            end = line.find("*/", start + 2)
            if end < 0:
                line, in_block = line[:start], True
                break
            line = line[:start] + line[end + 2:]
        slash = line.find("//")
        if slash >= 0:
            line = line[:slash]
        line = line.strip()
        if line:
            out.append(line)
    return out


def code(src):
    return "\n".join(code_lines(src))


def body(src, signature):
    """按花括号配平取出一个方法体；取不到返回空串，让断言失败而不是抛异常。"""
    i = src.find(signature)
    if i < 0:
        return ""
    j = src.find("{", i)
    if j < 0:
        return ""
    depth = 0
    for k in range(j, len(src)):
        if src[k] == "{":
            depth += 1
        elif src[k] == "}":
            depth -= 1
            if depth == 0:
                return src[j:k + 1]
    return ""
