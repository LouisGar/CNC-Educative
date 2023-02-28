package sourcerabbit.gcode.sender.UI;

/**
 *
 * @author arnau
 */
public class Utilisateurs {
    //attribut
    public static int level;

    //constructeur 
    public Utilisateurs(int level) {
        this.level = level;
    }

    static int getLevel() {
        return level;
    }

    public void setLevel(int level) {
        this.level = level;
    }

   
}
