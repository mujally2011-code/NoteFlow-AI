'use strict';
let accessToken = ''; // held in memory only, never localStorage or URL
const $ = (id) => document.getElementById(id);
function say(message) { $('feedback').textContent = message; }
async function api(path, opts={}) {
  const res = await fetch('/v1/enterprise' + path, {
    ...opts,
    headers: {'Authorization':'Bearer '+accessToken,'Content-Type':'application/json', ...(opts.headers||{})},
    cache: 'no-store'
  });
  const result=await res.json();
  if(!res.ok) throw Error(typeof result.detail==='string'?result.detail:'HTTP '+res.status);
  return result;
}
function cell(row, value) {const td=document.createElement('td');td.textContent=String(value||'');row.append(td);return td;}
async function refresh() {
  const r=await api('/dashboard');$('portal').classList.remove('hidden');$('signin').classList.add('hidden');
  $('orgname').textContent=r.organization;$('memberscount').textContent=r.members;$('importscount').textContent=r.imports;
  $('connected').textContent='الحسابات المرتبطة: '+r.connected.join(', ');
  $('userspanel').classList.toggle('hidden',r.role!=='admin');
  const results=await api('/imports');const table=$('imports');table.replaceChildren();
  for(const entry of results.imports){const tr=document.createElement('tr');cell(tr,entry.title);cell(tr,entry.provider);const td=cell(tr,'');const btn=document.createElement('button');btn.textContent='عرض النص';btn.addEventListener('click',async()=>{try{const data=await api('/imports/'+encodeURIComponent(entry.id));$('transcripttitle').textContent=data.title;$('transcript').textContent=data.transcript;}catch(e){say(e.message)}});td.append(btn);const download=document.createElement('button');download.textContent='تنزيل TXT';download.addEventListener('click',async()=>{try{const response=await fetch('/v1/enterprise/imports/'+encodeURIComponent(entry.id)+'/export',{headers:{'Authorization':'Bearer '+accessToken},cache:'no-store'});if(!response.ok)throw Error('Export failed');const blob=await response.blob();const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download='transcript-'+entry.id+'.txt';a.click();URL.revokeObjectURL(url);}catch(e){say(e.message)}});td.append(download);table.append(tr);}
  if(r.role==='admin'){const members=await api('/members');const table=$('members');table.replaceChildren();for(const m of members.members){const row=document.createElement('tr');cell(row,m.email);cell(row,m.role);table.append(row)}}
}
$('loginform').addEventListener('submit', async(event)=>{event.preventDefault();const form=new FormData(event.target);try{const r=await api('/login',{method:'POST',body:JSON.stringify({email:form.get('email'),password:form.get('password')})});accessToken=r.access_token;event.target.reset();await refresh();say('تم الدخول بنجاح')}catch(e){say(e.message)}});
$('logout').addEventListener('click',()=>{accessToken='';$('portal').classList.add('hidden');$('signin').classList.remove('hidden');$('transcript').textContent='';say('تم تسجيل الخروج')});
document.querySelectorAll('[data-connect]').forEach(btn=>btn.addEventListener('click',async()=>{try{const r=await api('/integrations/'+btn.dataset.connect+'/authorization-url');window.open(r.url,'_blank','noopener,noreferrer');say('أكمل الموافقة في النافذة الجديدة ثم اضغط تحديث')}catch(e){say(e.message)}}));
$('browse').addEventListener('click',async()=>{try{const provider=$('provider').value;const q=new URLSearchParams({meeting_id:$('meeting').value,from_date:$('fromdate').value,to_date:$('todate').value});const data=await api('/integrations/'+provider+'/items?'+q);const root=$('items');root.replaceChildren();for(const entry of data.items){const line=document.createElement('p');const text=document.createElement('span');text.textContent=entry.title+' — '+entry.type+' | ';const button=document.createElement('button');button.textContent='استيراد';button.addEventListener('click',async()=>{try{await api('/integrations/'+provider+'/import',{method:'POST',body:JSON.stringify({meeting_id:entry.meeting_id,file_id:entry.id,from_date:$('fromdate').value,to_date:$('todate').value})});say('تم الاستيراد');await refresh()}catch(e){say(e.message)}});line.append(text,button);root.append(line);}if(!data.items.length) say('لا توجد ملفات متاحة لهذا الحساب والنطاق')}catch(e){say(e.message)}});
$('adduser').addEventListener('submit',async(e)=>{e.preventDefault();try{const f=new FormData(e.target);await api('/members',{method:'POST',body:JSON.stringify(Object.fromEntries(f.entries()))});e.target.reset();await refresh();say('تمت إضافة العضو')}catch(err){say(err.message)}});
$('reload').addEventListener('click',()=>refresh().catch(e=>say(e.message)));
