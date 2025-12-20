package metaParser;

import java.util.Optional;
import java.util.regex.Pattern;

public interface TokenMatch{
  Optional<String> apply(String input, int start);
  static TokenMatch fromRegex(String regex){
    var r= Pattern.compile(regex);
    return new TokenMatch(){
      public String toString(){ return regex; }
      public Optional<String> apply(String input, int start){
        var matcher = r.matcher(input);
        matcher.useTransparentBounds(true);
        matcher.region(start, input.length());
        if ( !matcher.lookingAt()){ return Optional.empty(); }
        return Optional.of(input.substring(start, matcher.end()));
      }
    };
  }
}