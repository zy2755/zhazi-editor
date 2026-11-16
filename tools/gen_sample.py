# -*- coding: utf-8 -*-
"""生成一个用于测试的示例 docx（手工构造最小 OOXML 包）。"""
import zipfile, os, sys

XMLDECL = '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\r\n'

CT = XMLDECL + (
    '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
    '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
    '<Default Extension="xml" ContentType="application/xml"/>'
    '<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>'
    '</Types>'
)

RELS = XMLDECL + (
    '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">'
    '<Relationship Id="rId1" '
    'Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" '
    'Target="word/document.xml"/>'
    '</Relationships>'
)

DOC = XMLDECL + (
    '<w:document '
    'xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" '
    'xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships" '
    'xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006" '
    'mc:Ignorable="w14 wp14">'
    '<w:body>'
    # 段落 1：三个 run，不同颜色/字号
    '<w:p>'
    '<w:r><w:rPr><w:color w:val="FF0000"/><w:sz w:val="21"/><w:szCs w:val="21"/></w:rPr><w:t>保留</w:t></w:r>'
    '<w:r><w:rPr><w:sz w:val="21"/><w:szCs w:val="21"/></w:rPr><w:t xml:space="preserve">适量空白</w:t></w:r>'
    '<w:r><w:t>。帽子、发饰等标志性部件应完整呈现。</w:t></w:r>'
    '</w:p>'
    # 段落 2：普通 run + 加粗 run + 普通 run
    '<w:p>'
    '<w:r><w:t>采用</w:t></w:r>'
    '<w:r><w:rPr><w:b/><w:bCs/></w:rPr><w:t>潦草</w:t></w:r>'
    '<w:r><w:t>、简省的粗蜡笔与油画棒画法，像随手快速画出的可爱小涂鸦。</w:t></w:r>'
    '<w:bookmarkStart w:id="0" w:name="bm1"/>'
    '<w:bookmarkEnd w:id="0"/>'
    '</w:p>'
    # 段落 3：含 tab 的受保护 run
    '<w:p>'
    '<w:r><w:t>轮廓用</w:t></w:r>'
    '<w:r><w:tab/><w:t>粗重</w:t></w:r>'
    '<w:r><w:t>、不规则的黑色笔触。</w:t></w:r>'
    '</w:p>'
    # 空段落
    '<w:p/>'
    # 段落 4：文本中带实体与空格
    '<w:p>'
    '<w:r><w:t xml:space="preserve">  A&amp;B &lt;测试&gt; 尾部空格 </w:t></w:r>'
    '</w:p>'
    '<w:sectPr/>'
    '</w:body>'
    '</w:document>'
)

out = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(__file__), '..', 'sample', '示例.docx')
out = os.path.abspath(out)
os.makedirs(os.path.dirname(out), exist_ok=True)
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
    z.writestr('[Content_Types].xml', CT)
    z.writestr('_rels/.rels', RELS)
    z.writestr('word/document.xml', DOC)
print('written:', out)
