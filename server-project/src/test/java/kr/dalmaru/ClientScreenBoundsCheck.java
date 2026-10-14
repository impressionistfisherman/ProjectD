package kr.dalmaru;
import java.nio.file.*;
import java.net.URLClassLoader;
import javax.tools.ToolProvider;
public final class ClientScreenBoundsCheck {
    public static void main(String[] args)throws Exception {
        Path base=Path.of("src/client/java/kr/dalmaru/client");String skill=Files.readString(base.resolve("DalmaruSkillScreen.java")),run=Files.readString(base.resolve("DalmaruRunScreen.java"));
        Path dir=Files.createTempDirectory("client-screen-bounds-");Path file=dir.resolve("ScreenHarness.java");
        Files.writeString(file,HARNESS.replace("/* SKILL */",method(skill,"protected void init()" )).replace("/* RUN */",method(run,"protected void init()")));
        if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",dir.toString(),file.toString())!=0)throw new AssertionError("Screen fixture compile failed");
        try(var loader=new URLClassLoader(new java.net.URL[]{dir.toUri().toURL()},ClassLoader.getPlatformClassLoader())){loader.loadClass("ScreenHarness").getMethod("run").invoke(null);}
        System.out.println("Actual skill/run layout bounds and overlap PASS (320x240,427x240,640x360,960x540)");
    }
    private static String method(String source,String signature){int start=source.indexOf(signature),open=source.indexOf('{',start),depth=1,end=open+1;for(;depth>0;end++){char c=source.charAt(end);if(c=='{')depth++;else if(c=='}')depth--;}return source.substring(start,end);}
    private static final String HARNESS="""
        import java.util.*;
        class Component{static String literal(String s){return s;}}
        class Button{int x,y,w,h;boolean active=true;static Button builder(String s,java.util.function.Consumer<Button> action){return new Button();}Button bounds(int x,int y,int w,int h){this.x=x;this.y=y;this.w=w;this.h=h;return this;}Button build(){return this;}}
        class Screen{int width,height;Screen minecraft=this;List<Button> buttons=new ArrayList<>();protected void init(){}void setScreen(Screen s){}void onClose(){}Button addRenderableWidget(Button b){buttons.add(b);return b;}void tick(){}}
        class Profile{String profession(){return "WARRIOR";}}
        class Response{Profile profile(){return new Profile();}}
        class ClientProfileState{static Response current(){return new Response();}}
        enum CharacterProfession{WARRIOR}
        class SkillDefinition{String id(){return "skill";}String koreanName(){return "skill";}static List<SkillDefinition>forProfession(CharacterProfession p){return java.util.stream.IntStream.range(0,8).mapToObj(i->new SkillDefinition()).toList();}}
        class DalmaruClient{static void requestSkill(String s){}static void requestSkillRanks(){}static void requestLoadout(){}static void requestRun(String s){}static void readyRun(){}}
        class ClientRunState{static String status(){return "READY";}}
        class SkillScreen extends Screen{Screen previous;List<Button>slotButtons=new ArrayList<>(),learnButtons=new ArrayList<>();List<SkillDefinition>skills=List.of();void cycle(int i){}/* SKILL */}
        class RunScreen extends Screen{Button status,firstStep,training,ready;/* RUN */}
        public class ScreenHarness{
        static void require(boolean condition,String reason){if(!condition)throw new AssertionError(reason);}
        public static void run(){for(int[]size:new int[][]{{320,240},{427,240},{640,360},{960,540}})for(Screen screen:List.of(new SkillScreen(),new RunScreen())){
        screen.width=size[0];screen.height=size[1];screen.init();require(screen.buttons.size()==(screen instanceof SkillScreen?14:5),"Missing actions");
        for(var b:screen.buttons)require(b.x>=0&&b.y>=0&&b.w>0&&b.h>0&&b.x+b.w<=screen.width&&b.y+b.h<=screen.height,"Clipped action at "+size[0]+"x"+size[1]);
        for(int i=0;i<screen.buttons.size();i++)for(int j=i+1;j<screen.buttons.size();j++){var a=screen.buttons.get(i);var b=screen.buttons.get(j);require(a.x+a.w<=b.x||b.x+b.w<=a.x||a.y+a.h<=b.y||b.y+b.h<=a.y,"Overlapping actions at "+size[0]+"x"+size[1]);}
        }}
        }
        """;
}
