from pathlib import Path
import re,xml.etree.ElementTree as ET
root=Path(__file__).resolve().parent.parent
ET.parse(root/'backend/pom.xml')
sql=(root/'backend/src/main/resources/db/migration/V1__initial_schema.sql').read_text()
tables={name:set(re.findall(r'^\s*(\w+)\s+(?:uuid|varchar|text|integer|bigint|timestamptz|double)',body,re.M)) for name,body in re.findall(r'create table (\w+) \((.*?)\n\);',sql,re.S)}
for table,column in re.findall(r'alter table (\w+) add column (\w+)',sql):tables[table].add(column)
count=0
for p in (root/'backend/src/main/java').rglob('*.java'):
 s=p.read_text();m=re.search(r'@Table\(name="(\w+)"\)',s)
 if not m:continue
 expected={'id','created_at','version'}|set(re.findall(r'@Column\(name="(\w+)"',s))
 assert expected==tables[m[1]],(p,expected^tables[m[1]])
 count+=1
assert count==12
assert 'TODO' not in ''.join(p.read_text() for p in (root/'backend/src/main/java').rglob('*.java'))
print(f'POM XML and all {count} entity/migration column mappings validated')
