package uk.oczadly.karl.csgsi.state;

import com.google.gson.Gson;
import uk.oczadly.karl.csgsi.internal.Util;

import static org.junit.Assert.*;

public class GameStateBaseTest {
    
    public final static Gson GSON = Util.createGsonObject();
    
    
    public static GameState deserilizeState(String json) {
        return GSON.fromJson(json, GameState.class);
    }
    
    public static <T extends Enum> void testEnums(Class<T> clazz, String... values) {
        //Test values
        for (String name : values) {
            T enumVal = GSON.fromJson("\"" + name + "\"", clazz);
            
            assertNotNull("Enum value \"" + name + "\" of " + clazz.getCanonicalName() + " couldn't be deserialized",
                    enumVal);
        }
        
        //Test null
        T nullVal = GSON.fromJson("\"slugs8984328942\"", clazz);
        assertNull("Unknown enum value was deserialized for " + clazz.getCanonicalName(),
                nullVal);
    }
    
}
