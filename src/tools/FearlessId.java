package tools;

/// The one place naming a Fearless version.
/// "fearless"+versionId is assumed to be a unique identifier, so two versions can sit
/// side by side and never meet:
/// - binFolder holds the code: it is the name of the deployed app image;
/// - dataFolder holds everything the manager writes.
/// The deploy names the app image from here and the manager hardcodes the same two
/// names, so neither has to be told where the other put things.
public final class FearlessId{
  private FearlessId(){}
  public static final String versionId= "0_1";
  public static final String binFolder= "fearlessBin"+versionId;
  public static final String dataFolder= "fearless"+versionId;
}
