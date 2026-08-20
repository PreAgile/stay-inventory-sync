#!/usr/bin/env python3
"""PR 본문의 "리뷰 판단" 절을 검증한다.

이 저장소의 리뷰 주체는 사람이다. 봇 리뷰 유무와 무관하게
작성자가 내용을 읽고 수락/질문/이견 중 하나를 명시해야 머지할 수 있다.

- 수락 → 통과
- 질문 → 실패 (답변 전까지 머지하지 않는다)
- 이견 → 실패 (합의 전까지 머지하지 않는다)
- 미기재 / 복수 선택 / 근거 공백 → 실패

PR 본문은 환경변수로만 받는다. 셸 보간을 거치지 않으므로 주입 위험이 없다.
"""

import os
import re
import sys

START = "<!-- review-verdict:start -->"
END = "<!-- review-verdict:end -->"

VERDICTS = [
    ("accepted", "수락", "읽었고 이대로 머지한다"),
    ("awaiting-answer", "질문", "답변 전까지 머지하지 않는다"),
    ("objection", "이견", "합의 전까지 머지하지 않는다"),
]

EVIDENCE_HEADING = "### 무엇을 읽고 판단했는가"
CONTENT_HEADING = "### 판단 내용"
MIN_CHARS = 20

HTML_COMMENT = re.compile(r"<!--.*?-->", re.DOTALL)


def fail(message: str, *hints: str) -> None:
    print(f"::error title=Review Gate::{message}")
    print(f"\n✗ {message}\n")
    for hint in hints:
        print(f"  {hint}")
    print(
        "\n  PR 본문의 '리뷰 판단' 절을 채운 뒤 저장하면 이 검사가 다시 실행된다."
        "\n  근거: CONTRIBUTING.md — 리뷰 판단 게이트\n"
    )
    sys.exit(1)


def set_output(key: str, value: str) -> None:
    path = os.environ.get("GITHUB_OUTPUT")
    if path:
        with open(path, "a", encoding="utf-8") as handle:
            handle.write(f"{key}={value}\n")


def section_text(block: str, heading: str, next_headings: list) -> str:
    """heading 아래부터 다음 heading(또는 블록 끝)까지의 실제 텍스트."""
    if heading not in block:
        return ""
    tail = block.split(heading, 1)[1]
    for other in next_headings:
        if other in tail:
            tail = tail.split(other, 1)[0]
    tail = HTML_COMMENT.sub("", tail)
    return tail.strip()


def main() -> None:
    body = os.environ.get("PR_BODY") or ""

    if START not in body or END not in body:
        fail(
            "PR 본문에 '리뷰 판단' 절이 없다.",
            "PR 템플릿(.github/pull_request_template.md)의 review-verdict 블록을 그대로 붙여넣는다.",
            "템플릿을 지우고 작성했다면 다시 넣어야 한다.",
        )

    block = body.split(START, 1)[1].split(END, 1)[0]

    checked = [
        slug
        for slug, label, _ in VERDICTS
        if re.search(rf"^\s*-\s*\[[xX]\]\s*\*\*{label}\*\*", block, re.MULTILINE)
    ]

    if not checked:
        fail(
            "리뷰 판단이 선택되지 않았다.",
            "수락 / 질문 / 이견 중 하나를 체크한다.",
            "봇 리뷰가 없어도 이 절은 필수다. 리뷰 주체는 사람이다.",
        )

    if len(checked) > 1:
        fail(
            f"리뷰 판단이 {len(checked)}개 선택됐다: {', '.join(checked)}",
            "하나만 체크한다. 판단은 하나여야 기록으로서 의미가 있다.",
        )

    verdict = checked[0]
    label_ko = next(label for slug, label, _ in VERDICTS if slug == verdict)

    evidence = section_text(block, EVIDENCE_HEADING, [CONTENT_HEADING])
    content = section_text(block, CONTENT_HEADING, [])

    if len(evidence) < MIN_CHARS:
        fail(
            "'무엇을 읽고 판단했는가'가 비어 있거나 너무 짧다.",
            f"최소 {MIN_CHARS}자. 어떤 파일·어떤 주장을 근거로 판단했는지 구체적으로 적는다.",
            "'확인했습니다'는 판단이 아니다.",
        )

    if len(content) < MIN_CHARS:
        fail(
            "'판단 내용'이 비어 있거나 너무 짧다.",
            f"최소 {MIN_CHARS}자.",
            "수락이면 무엇이 결정적이었는지, 질문이면 어떤 답을 들어야 수락할 수 있는지 적는다.",
        )

    set_output("verdict", verdict)
    set_output("label", f"review: {verdict}")

    print(f"판단: {label_ko} ({verdict})")
    print(f"근거: {evidence.splitlines()[0][:80]}")

    if verdict != "accepted":
        reason = next(desc for slug, _, desc in VERDICTS if slug == verdict)
        fail(
            f"리뷰 판단이 '{label_ko}'이므로 머지할 수 없다 — {reason}.",
            "해소되면 판단을 '수락'으로 바꾸고 그 근거를 남긴다.",
            "판단을 바꿀 때 기존 내용을 지우지 않는다. 무엇이 해소됐는지가 기록이다.",
        )

    print("\n✓ 수락 — 머지 가능\n")


if __name__ == "__main__":
    main()
