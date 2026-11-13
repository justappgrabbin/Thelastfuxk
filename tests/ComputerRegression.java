package app.lastfuck;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import org.json.*;
public final class ComputerRegression {
 static int checks;
 static void check(boolean condition,String name) { if(!condition)throw new AssertionError(name);checks++;System.out.println("PASS "+name); }
 public static void main(String[] args)throws Exception {
  Path home=Files.createTempDirectory("cynthia-computer");
  ConversationLog log=new ConversationLog(home.toFile());
  log.append("user","hello","job1","Queued");log.append("assistant","Hi there","job1","Reply");
  check(new ConversationLog(home.toFile()).tail(100).length()==2,"chat survives a new log instance");
  check(log.tail(1).getJSONObject(0).getString("role").equals("assistant"),"history tail returns newest conversation");
  Files.write(home.resolve("conversation.jsonl"),"broken unfinished row\n".getBytes(StandardCharsets.UTF_8),StandardOpenOption.APPEND);
  check(log.tail(100).length()==2,"damaged history row does not hide saved chat");
  Path work=Files.createDirectory(home.resolve("workspace"));
  Files.createDirectory(work.resolve("outputs"));Files.writeString(work.resolve("outputs/welcome.html"),"<h1>Hello</h1>");
  WorkspaceBrowser files=new WorkspaceBrowser(work.toFile());
  check(files.list("").getJSONArray("items").getJSONObject(0).getBoolean("directory"),"workspace browser lists real folders");
  check(files.preview("outputs/welcome.html").getString("kind").equals("html"),"saved HTML is available for preview");
  check(files.preview("outputs/welcome.html").getString("text").equals("<h1>Hello</h1>"),"preview reflects actual saved bytes");
  Files.write(work.resolve("sample.png"),new byte[]{1,2,3});
  check(files.preview("sample.png").getString("data").equals("data:image/png;base64,AQID"),"image preview encodes local bytes");
  boolean blocked=false;try{files.preview("../conversation.jsonl");}catch(java.io.IOException e){blocked=true;}
  check(blocked,"file preview cannot read outside workspace");
  Files.createSymbolicLink(work.resolve("escape"),home.resolve("conversation.jsonl"));
  blocked=false;try{files.preview("escape");}catch(java.io.IOException e){blocked=true;}
  check(blocked,"file preview cannot escape through symlinks");
  Thread waiting=new Thread(()->{try{ActionReview.await("write hello.py");}catch(InterruptedException e){throw new RuntimeException(e);}});
  waiting.start();for(int i=0;i<100&&ActionReview.pending.isEmpty();i++)Thread.sleep(5);
  check(!ActionReview.pending.isEmpty()&&waiting.isAlive(),"autonomy-off action waits for explicit review");
  ActionReview.decide(true);waiting.join(2000);
  check(!waiting.isAlive()&&ActionReview.pending.isEmpty(),"approval releases action and clears prompt");
  boolean[] declined={false};Thread denied=new Thread(()->{try{ActionReview.await("write denied.py");}catch(InterruptedException e){declined[0]=true;}});
  denied.start();for(int i=0;i<100&&ActionReview.pending.isEmpty();i++)Thread.sleep(5);ActionReview.decide(false);denied.join(2000);
  check(declined[0]&&!denied.isAlive(),"declined action does not proceed");
  CodingAgent.Model model=messages->"{\"reply\":\"Hello! What are we making?\"}";
  CodingAgent.Tools tools=new CodingAgent.Tools(){public String available(){return "read(path)";}public JSONObject call(String name,JSONObject values){throw new AssertionError("greeting must not use tools");}};
  CodingAgent.Result answer=new CodingAgent(model,tools,x->{}).converse("hello","");
  check(answer.status.equals("Reply")&&answer.output.startsWith("Hello!"),"conversational reply bypasses task completion loop");
  System.out.println(checks+" computer/chat/review checks passed");
 }
}
