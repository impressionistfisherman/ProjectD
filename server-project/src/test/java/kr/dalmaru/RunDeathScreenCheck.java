package kr.dalmaru;

import java.nio.file.*;
import java.net.URLClassLoader;
import javax.tools.ToolProvider;

public final class RunDeathScreenCheck {
    public static void main(String[] args)throws Exception{
        String source=Files.readString(Path.of("src/client/java/kr/dalmaru/client/mixin/RunDeathScreenMixin.java"));
        Path dir=Files.createTempDirectory("run-death-screen-");
        try{
            Path file=dir.resolve("DeathScreenHarness.java");
            Files.writeString(file,HARNESS.replace("/* METHODS */",method(source,"private void dalmaru$reviveButton(")+method(source,"private void dalmaru$reviveInfo(")));
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-d",dir.toString(),file.toString())!=0)throw new AssertionError("Death screen fixture compile failed");
            try(var loader=new URLClassLoader(new java.net.URL[]{dir.toUri().toURL()},ClassLoader.getPlatformClassLoader())){loader.loadClass("DeathScreenHarness").getMethod("run").invoke(null);}
        }finally{try(var paths=Files.walk(dir)){for(var path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(path);}}
        System.out.println("Actual death UI countdown/click/spectator/field/bounds PASS");
    }
    private static String method(String source,String signature){int start=source.indexOf(signature),open=source.indexOf('{',start),depth=1,end=open+1;for(;depth>0;end++){char c=source.charAt(end);if(c=='{')depth++;else if(c=='}')depth--;}return source.substring(start,end);}
    private static final String HARNESS="""
        import java.util.*;
        class Component{String text;Component(String text){this.text=text;}static Component literal(String text){return new Component(text);}}
        class Button{boolean active;String text="vanilla";void setMessage(Component component){text=component.text;}}
        class CallbackInfo{}
        record Status(boolean dead,int remainingRevives,int clickTicks,int autoTicks,boolean failed){}
        class DalmaruClient{static Status status;static Status runReviveState(){return status;}}
        class Font{int width(String text){return text.length()*6;}}
        class GuiGraphicsExtractor{List<String>texts=new ArrayList<>();List<int[]> positions=new ArrayList<>();void text(Font font,String text,int x,int y,int color,boolean shadow){texts.add(text);positions.add(new int[]{x,y,font.width(text)});}}
        public class DeathScreenHarness{
            List<Button>exitButtons=new ArrayList<>();Font font=new Font();int width,height;
            /* METHODS */
            static void check(boolean value){if(!value)throw new AssertionError("Death UI differs");}
            public static void run(){
                var h=new DeathScreenHarness();var button=new Button();h.exitButtons.add(button);
                DalmaruClient.status=null;h.dalmaru$reviveButton(null);check(!button.active&&button.text.equals("vanilla"));
                DalmaruClient.status=new Status(true,2,100,300,false);h.dalmaru$reviveButton(null);check(!button.active&&button.text.contains("5초"));
                DalmaruClient.status=new Status(true,2,1,201,false);h.dalmaru$reviveButton(null);check(!button.active&&button.text.contains("1초"));
                DalmaruClient.status=new Status(true,2,0,200,false);h.dalmaru$reviveButton(null);check(button.active&&button.text.equals("부활"));
                for(int[] size:new int[][]{{320,240},{427,240},{640,360},{960,540}}){
                    h.width=size[0];h.height=size[1];var g=new GuiGraphicsExtractor();h.dalmaru$reviveInfo(g,0,0,0,null);
                    check(g.texts.get(0).contains("2회")&&g.texts.get(1).contains("10초"));
                    for(var p:g.positions)check(p[0]>=0&&p[0]+p[2]<=h.width&&p[1]>=h.height/4+116&&p[1]+9<=h.height);
                }
                DalmaruClient.status=new Status(true,0,0,0,true);h.dalmaru$reviveButton(null);check(button.active&&button.text.equals("관전"));
                var g=new GuiGraphicsExtractor();h.dalmaru$reviveInfo(g,0,0,0,null);check(g.texts.get(1).contains("부활 불가"));
                DalmaruClient.status=new Status(false,0,0,0,false);button.text="field";button.active=false;h.dalmaru$reviveButton(null);g=new GuiGraphicsExtractor();h.dalmaru$reviveInfo(g,0,0,0,null);check(button.text.equals("field")&&!button.active&&g.texts.isEmpty());
            }
        }
        """;
}
