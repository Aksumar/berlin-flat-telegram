from dataclasses import dataclass


@dataclass(frozen=True)
class Listing:
    id: str
    title: str
    url: str
    details: str = ""
