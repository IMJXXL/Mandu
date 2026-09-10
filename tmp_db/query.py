import sqlite3
con = sqlite3.connect(r'C:\Android\MangaReader\tmp_db\mr.db')
rows = con.execute("SELECT title, coverPath FROM manga_books WHERE fileType='dir' AND title LIKE '%download%'").fetchall()
for title, cp in rows:
    print('TITLE:', title)
    print('CVR_FULL:', cp)
    parts = cp.split('|')
    print('PARTS count:', len(parts))
    for i, p in enumerate(parts):
        print(f'  [{i}]: {p}')
