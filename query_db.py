import sqlite3, os
tmp = os.environ['TEMP']
db_path = os.path.join(tmp, 'manga_full.db')
conn = sqlite3.connect(db_path)
c = conn.cursor()

c.execute("PRAGMA table_info(manga_books)")
cols = c.fetchall()
col_names = [col[1] for col in cols]

c.execute('SELECT * FROM manga_books ORDER BY id')
rows = c.fetchall()
print('Total books: ' + str(len(rows)))
print()
for r in rows:
    book = dict(zip(col_names, r))
    bid = book.get('id')
    title = book.get('title')
    fp = book.get('filePath')
    op = book.get('originalPath')
    src = book.get('source')
    ft = book.get('fileType')
    cp = book.get('coverPath')
    
    print('ID=' + str(bid) + ' | ' + str(title))
    print('  fileType=' + str(ft) + ' | source=' + str(src))
    
    fp_s = (str(fp)[:150] + '...') if fp and len(str(fp)) > 150 else str(fp)
    op_s = (str(op)[:150] + '...') if op and len(str(op)) > 150 else str(op)
    cp_s = (str(cp)[:100] + '...') if cp and len(str(cp)) > 100 else str(cp)
    
    print('  filePath=' + fp_s)
    print('  originalPath=' + op_s)
    print('  coverPath=' + cp_s)
    
    if op and str(op).startswith('a:'):
        print('  *** SAF format (a:) ***')
    elif op and str(op).startswith('/storage'):
        print('  *** filesystem path ***')
    elif op and str(op).startswith('content://'):
        print('  *** content URI ***')
    print()

# Also check download_tasks
c.execute('SELECT * FROM download_tasks')
dt_cols = [d[0] for d in c.description]
dt_rows = c.fetchall()
print('=== download_tasks (' + str(len(dt_rows)) + ' rows) ===')
for r in dt_rows:
    print(dict(zip(dt_cols, r)))

conn.close()