package kr.dalmaru;

import java.nio.file.*;
import java.net.URLClassLoader;
import javax.tools.ToolProvider;
import kr.dalmaru.account.CharacterProfession;
import kr.dalmaru.skill.SkillDefinition;

public final class SkillPresentationCheck {
    public static void main(String[] args) throws Exception {
        String spec=Files.readString(Path.of("../docs/PILOT_CONTENT_SPEC.md"));
        int checked=0;
        for(var profession:CharacterProfession.values())for(var skill:SkillDefinition.forProfession(profession)){
            String row=spec.lines().filter(line->line.startsWith("| `"+skill.id()+"`")).findFirst().orElseThrow();
            String[] columns=row.split("\\|");
            String expected="기력 "+columns[2].trim()+" / 재사용 "+columns[3].trim()+" / 1~5랭크: "+columns[4].trim().replace("`", "");
            if(!expected.equals(skill.description()))throw new AssertionError("Description differs: "+skill.id());
            checked++;
        }
        if(checked!=24)throw new AssertionError("Missing skill descriptions");
        String source=Files.readString(Path.of("src/client/java/kr/dalmaru/client/DalmaruSkillScreen.java"));
        int start=source.indexOf("public void tick()"),open=source.indexOf('{',start),depth=1,end=open+1;
        for(;depth>0;end++){char c=source.charAt(end);if(c=='{')depth++;else if(c=='}')depth--;}
        Path dir=Files.createTempDirectory("skill-presentation-");
        try {
            Path file=dir.resolve("PresentationHarness.java");
            Files.writeString(file,HARNESS.replace("/* TICK */",source.substring(start,end)));
            if(ToolProvider.getSystemJavaCompiler().run(null,null,null,"-classpath",System.getProperty("java.class.path"),"-d",dir.toString(),file.toString())!=0)throw new AssertionError("Fixture compile failed");
            try(var loader=new URLClassLoader(new java.net.URL[]{dir.toUri().toURL()},SkillPresentationCheck.class.getClassLoader())){
                loader.loadClass("PresentationHarness").getMethod("run").invoke(null);
            }
        } finally {
            try(var paths=Files.walk(dir)){for(var path:paths.sorted(java.util.Comparator.reverseOrder()).toList())Files.delete(path);}
        }
        System.out.println("Skill descriptions (24), actual tick maximum-rank/tooltip/update PASS");
    }
    private static final String HARNESS="""
        import java.util.*;
        import kr.dalmaru.skill.SkillDefinition;
        import kr.dalmaru.account.CharacterProfession;
        class Component {String text;Component(String text){this.text=text;}static Component literal(String text){return new Component(text);}}
        class Tooltip {Component text;static Tooltip create(Component text){var t=new Tooltip();t.text=text;return t;}}
        class Button {Component message;boolean active=true;Tooltip tooltip;void setMessage(Component message){this.message=message;}void setTooltip(Tooltip tooltip){this.tooltip=tooltip;}}
        class ClientSkillState {static int rank;static int rank(String id){return rank;}}
        record Loadout(List<String> slots,boolean editable){}
        class ClientLoadoutState {static Loadout current(){return null;}static boolean waiting(){return false;}}
        public class PresentationHarness {
            List<SkillDefinition> skills=SkillDefinition.forProfession(CharacterProfession.WARRIOR);
            List<Button> learnButtons=new ArrayList<>(),slotButtons=new ArrayList<>();
            /* TICK */
            public static void run(){
                var h=new PresentationHarness();for(var skill:h.skills)h.learnButtons.add(new Button());
                ClientSkillState.rank=5;h.tick();
                for(var button:h.learnButtons)if(button.active||!button.message.text.contains("최대")||button.tooltip==null||button.tooltip.text.text.isBlank())throw new AssertionError("Maximum rank/tooltip missing");
                ClientSkillState.rank=4;h.tick();
                for(var button:h.learnButtons)if(!button.active||!button.message.text.contains("4/5 [+1]"))throw new AssertionError("Rank update did not re-enable learning");
                ClientSkillState.rank=0;h.tick();
                for(var button:h.learnButtons)if(!button.active||!button.message.text.contains("0/5"))throw new AssertionError("Unlearned skill cannot be learned");
            }
        }
        """;
}
