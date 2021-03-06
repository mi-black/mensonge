package mensonge.core.BaseDeDonnees;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import mensonge.core.tools.DataBaseObservable;

/**
 * Classe permettant les interraction avec la base de donnees
 * 
 * @author Azazel
 * 
 */
public class BaseDeDonnees extends DataBaseObservable
{

	public static final int EXPORTER_ENREGISTREMENT = 2;
	public static final int EXPORTER_BASE = 1;

	/**
	 * Le logger
	 */
	private static Logger logger = Logger.getLogger("BDD");
	/**
	 * Permet d'instancier des objet qui permettrons de faire des requetes.
	 */
	private Connection connexion = null;
	/**
	 * Nom du fichier fourni pour la base.
	 */
	private String fileName = null;

	/**
	 * Constructeur de base.
	 * 
	 * @param baseDeDonnees
	 *            Chaine de caractére indiquant le nom du fichier de la base de donnee.
	 * @throws DBException
	 */
	public BaseDeDonnees(final String baseDeDonnees) throws DBException
	{
		fileName = baseDeDonnees;
	}

	/**
	 * Fonction permettant de se connecter au fichier de la base de donnee fourni au constructeur. De plus, cette
	 * fonction verifie la structure de la base.
	 * 
	 * @throws DBException
	 *             Envoie des exceptions dans le cas d'une erreur de connexion ou d'une mauvaise structure.
	 */
	public void connexion() throws DBException
	{
		try
		{
			// Lance la connection
			Class.forName("org.sqlite.JDBC");
			connexion = DriverManager.getConnection("jdbc:sqlite:" + fileName);
			// desactive l'autocommit ce qui est plus securisant et augmente la vitesse
			connexion.setAutoCommit(true);
		}
		catch (SQLException e)
		{
			throw new DBException("Erreur lors de l'initialisation de la connexion : " + e.getMessage(), 1);
		}
		catch (ClassNotFoundException e)
		{
			throw new DBException("Impossible de trouver le pilote pour la base de données : " + e.getMessage(), 1);
		}
		Statement stat = null;
		try
		// test la structure de la base
		{
			stat = connexion.createStatement();// creation du Statement
			stat.executeQuery("SELECT id, enregistrement, duree, taille, nom, idcat, idsuj FROM enregistrements;");
			stat.executeQuery("SELECT idcat, nomcat FROM categorie;");
			stat.executeQuery("SELECT idsuj, nomsuj FROM sujet;");
			// fermeture du Statement
			stat.close();
		}
		catch (SQLException e)
		{
			throw new DBException("Problème dans la structure de la base : " + e.getMessage(), 2);
		} finally
		{
			closeRessource(null, stat, null);
		}
	}

	/**
	 * Deconnecte de la base
	 * 
	 * @throws DBException
	 *             En cas d'erreur de deconnexion, l'etat de la connexion devient alors inconnu
	 */
	public void deconnexion() throws DBException
	{
		try
		{
			connexion.close();// On close la connexion
			connexion = null;// On remet a null pour des test future
		}
		catch (SQLException e)
		{
			throw new DBException("Probleme lors de la deconnexion de la base : " + e.getMessage(), 4);
		} finally
		{
			connexion = null;
		}
	}

	/**
	 * Importe un fichier de BaseDeDonnees sqlite dans la base donnee a laquelle l'objet est connecte. Les categories
	 * existantes (de meme nom) sont fusionnees. Les autres sont ajoutees.
	 * 
	 * @param cheminFichier
	 *            le fichier contenant la base a importer
	 * @throws DBException
	 */
	public void importer(final String cheminFichier) throws DBException
	{
		notifyInProgressAction("Importation de la base de données...");
		List<LigneEnregistrement> liste;
		Map<Integer, Integer> bijectionCategorie = new HashMap<Integer, Integer>();
		Map<Integer, Integer> bijectionSujet = new HashMap<Integer, Integer>();
		BaseDeDonnees baseImporte = new BaseDeDonnees(cheminFichier);

		baseImporte.connexion();

		liste = baseImporte.getListeSujet();
		for (LigneEnregistrement sujet : liste)
		{
			while (this.sujetExiste(sujet.getNomSuj()))
			{
				sujet.setNomSuj(sujet.getNomSuj() + ".new");
			}
			this.ajouterSujet(sujet.getNomSuj());
			bijectionSujet.put(sujet.getIdSuj(), this.getSujet(sujet.getNomSuj()));
			// ajouter au a la bijection des sujets
		}

		liste = baseImporte.getListeCategorie();
		for (LigneEnregistrement categorie : liste)
		{
			while (this.categorieExiste(categorie.getNomCat()))
			{
				categorie.setNomCat(categorie.getNomCat() + ".new");
			}
			this.ajouterCategorie(categorie.getNomCat());
			bijectionCategorie.put(categorie.getIdCat(), this.getCategorie(categorie.getNomCat()));
			// ajouter au a la bijection des sujets
		}

		liste = baseImporte.getListeEnregistrement();
		// Pour tous les enregistrements, on effectue la bijection des categories et des sujets
		for (LigneEnregistrement enregistrement : liste)
		{
			StringBuffer buffer = new StringBuffer(enregistrement.getNom());
			while (this.enregistrementExist(buffer.toString()))
			{
				buffer.append(".new");
			}
			byte sample[] = baseImporte.recupererEnregistrement(enregistrement.getId());
			int idCat = enregistrement.getIdCat();
			int idSuj = enregistrement.getIdSuj();
			idCat = bijectionCategorie.get(idCat);
			idSuj = bijectionSujet.get(idSuj);
			enregistrement.setIdCat(idCat);
			enregistrement.setIdSuj(idSuj);

			this.ajouterEnregistrement(buffer.toString(), enregistrement.getDuree(), enregistrement.getIdCat(), sample,
					enregistrement.getIdSuj());
		}
		notifyCompletedAction("La base de données a été importée");
	}

	/**
	 * exporte les enregistrement selon deux methodes. soit au format sqlite soit en wav. (ajoute à la fin du fichier
	 * wav le sujet et la categorie
	 * 
	 * @param cheminFichier
	 *            fichier dans lequel sera exporte la base.
	 * @param id
	 *            Correspond à l'identifiant de l'enregistrement à exporter dans le cas d'un type WAV
	 * @param type
	 *            Correspond au type d'exportation, 1 = SQLite, 2 = WAV
	 */
	public void exporter(final String cheminFichier, final int id, final int type) throws DBException// TODO a tester
	{
		if (connexion == null)
		{
			return;
		}
		if (type == EXPORTER_BASE)// Exporter la base vers un nouveau fichier
		{
			notifyInProgressAction("Exportation de la base de données...");
			File src = new File(fileName);
			File dest = new File(cheminFichier);
			if (!src.exists())// Verifie si le fichier existe bien
			{
				notifyFailedAction("Impossible d'exporter la base de données");
				throw new DBException("Le fichier de base de données (" + fileName + ") n'existe pas.", 3);
			}
			if (dest.exists())// verifie que la destination n'existe pas, auquel cas, on la supprime
			{
				dest.delete();
			}
			try
			{
				dest.createNewFile();// Création du nouveau fichier
			}
			catch (IOException e)
			{
				notifyFailedAction("Impossible d'exporter la base de données");
				throw new DBException("Impossible de créer le fichier de sortie: " + e.getMessage(), 3);
			}
			if (!copyFile(src, dest))
			{
				notifyFailedAction("Impossible d'exporter la base de données");
				throw new DBException("Impossible de copier le fichier de la base", 3);
			}
			notifyCompletedAction("La base de données a été exportée");
		}
		else if (type == EXPORTER_ENREGISTREMENT)// exporter un echantillon
		{
			notifyInProgressAction("Exportation de l'enregistrement...");
			File dest = new File(cheminFichier);
			if (dest.exists())// verifie que la destination n'existe pas, auquel cas, on la supprime
			{
				dest.delete();
			}
			try
			{
				dest.createNewFile();// Création du nouveau fichier
			}
			catch (IOException e)
			{
				notifyFailedAction("Impossible d'exporter l'enregistrement");
				throw new DBException("Impossible de créer le fichier de sortie : " + e.getMessage(), 3);
			}
			// récupérer un enregistrement
			byte[] echantillon = this.recupererEnregistrement(id);
			byte sujet = 0, categorie = 0;
			// coller l'enregistrement dans un fichier
			FileOutputStream destinationFile = null;
			List<LigneEnregistrement> liste = this.getListeEnregistrement();

			for (LigneEnregistrement ligne : liste)
			{
				if (ligne.getId() == id)
				{
					sujet = (byte) ligne.getIdCat();
					categorie = (byte) ligne.getIdSuj();
				}
			}

			try
			{
				destinationFile = new FileOutputStream(dest);
				destinationFile.write(echantillon);
				destinationFile.write(sujet);
				destinationFile.write(categorie);
				destinationFile.close();
			}
			catch (IOException e)
			{
				notifyFailedAction("Impossible d'exporter l'enregistrement");
				throw new DBException("Erreur lors de la copie de l'échantillon dans le fichier : " + e.getMessage(), 3);
			}
			notifyCompletedAction("La base de données a été exportée");
		}
	}

	/**
	 * Permet de recuperer toutes les information de tout les enregistrements avec les colonne suivante dans cette
	 * ordre: duree, taille, nom, nomcat, id
	 * 
	 * @return Le resultat sous forme d'objet ResultSet qui n'est parcourable qu'une fois.
	 * @throws DBException
	 */
	public List<LigneEnregistrement> getListeEnregistrement() throws DBException
	{
		if (connexion == null)
		{
			return null;
		}
		Statement stat = null;
		ResultSet rs = null;
		List<LigneEnregistrement> retour = null;
		List<String> colonne = new LinkedList<String>();
		colonne.add("duree");
		colonne.add("taille");
		colonne.add("nom");
		colonne.add("nomcat");
		colonne.add("id");
		colonne.add("nomsuj");
		colonne.add("idcat");
		colonne.add("idsuj");
		try
		{
			stat = connexion.createStatement(); // Creation du Statement

			rs = stat
					.executeQuery("SELECT duree, taille, nom, nomcat, id, nomsuj, en.idcat, en.idsuj FROM enregistrements en, categorie ca, sujet su WHERE en.idcat = ca.idcat AND en.idsuj = su.idsuj ORDER BY nomcat, nom;"); // Execution
																																																								// //
																																																								// de
																																																								// //
																																																								// requete
			retour = ResultatSelect.convertirResultatSet(rs, colonne);

		}
		catch (SQLException e)
		{
			throw new DBException("Erreur lors de la recuperation de la liste des enregistrement: " + e.getMessage(), 1);
		} finally
		{
			closeRessource(null, stat, rs);
		}
		return retour;
	}

	/**
	 * Permet de recuperer toutes les informations de tout les enregistrements d'une categorie avec les colonnes
	 * suivante: duree, taille, nom, nomcat, id
	 * 
	 * @return Le resultat sous forme d'objet ResultSet qui n'est parcourable qu'une fois.
	 * @throws DBException
	 */
	public List<LigneEnregistrement> getListeEnregistrementCategorie(final int idCat) throws DBException
	{
		if (connexion == null)
		{
			return null;
		}
		PreparedStatement ps = null;
		ResultSet rs = null;
		List<LigneEnregistrement> retour = null;
		List<String> colonne = new LinkedList<String>();
		colonne.add("duree");
		colonne.add("taille");
		colonne.add("nom");
		colonne.add("nomcat");
		colonne.add("id");
		colonne.add("nomsuj");
		colonne.add("idsuj");
		colonne.add("idcat");
		try
		{
			ps = connexion
					.prepareStatement("SELECT duree, taille, nom, nomcat, id, nomsuj, en.idsuj, en.idcat FROM enregistrements en, categorie ca, sujet su WHERE en.idcat = ca.idcat AND en.idsuj = su.idsuj AND en.idcat=? ORDER BY nom");
			ps.setInt(1, idCat);// on rempli les trous
			rs = ps.executeQuery();// On execute
			retour = ResultatSelect.convertirResultatSet(rs, colonne);
		}
		catch (SQLException e)
		{
			throw new DBException("Erreur lors de la recuperation de la liste des enregistrements: " + e.getMessage(),
					1);
		} finally
		{
			closeRessource(ps, null, rs);
		}
		return retour;
	}

	/**
	 * Permet de recuperer toutes les informations de tout les enregistrements d'une categorie avec les colonnes
	 * suivante: duree, taille, nom, nomcat, id
	 * 
	 * @return Le resultat sous forme d'objet ResultSet qui n'est parcourable qu'une fois.
	 * @throws DBException
	 */
	public List<LigneEnregistrement> getListeEnregistrementSujet(final int idSuj) throws DBException
	{
		if (connexion == null)
		{
			return null;
		}
		PreparedStatement ps = null;
		ResultSet rs = null;
		List<LigneEnregistrement> retour = null;
		List<String> colonne = new LinkedList<String>();
		colonne.add("duree");
		colonne.add("taille");
		colonne.add("nom");
		colonne.add("nomcat");
		colonne.add("id");
		colonne.add("nomsuj");
		colonne.add("idsuj");
		colonne.add("idcat");
		try
		{
			ps = connexion
					.prepareStatement("SELECT duree, taille, nom, nomcat, id, nomsuj, en.idsuj, en.idcat FROM enregistrements en, categorie ca, sujet su WHERE en.idcat = ca.idcat AND en.idsuj = su.idsuj AND en.idsuj=? ORDER BY nom");// Preparation
			// de
			// la
			// requete
			ps.setInt(1, idSuj);// on rempli les trous
			rs = ps.executeQuery();// On execute
			retour = ResultatSelect.convertirResultatSet(rs, colonne);
		}
		catch (SQLException e)
		{
			throw new DBException("Erreur lors de la recuperation de la liste des enregistrement: " + e.getMessage(), 1);
		} finally
		{
			closeRessource(ps, null, rs);
		}
		return retour;
	}

	/**
	 * Permet de recuperer le nombre d'enregistrement
	 * 
	 * @return le nombre d'enregistrement
	 * @throws DBException
	 */
	public int getNombreEnregistrement() throws DBException
	{
		if (connexion == null)
		{
			return -1;
		}
		Statement stat = null;
		ResultSet rs = null;
		try
		{
			stat = connexion.createStatement();// Creer le Statement
			rs = stat.executeQuery("SELECT count(1) FROM enregistrements;");// On execute la recherche
			int retour = rs.getInt(1);// On recupere le resultat
			rs.close();// On ferme les different objet
			stat.close();
			return retour;
		}
		catch (SQLException e)
		{
			throw new DBException("Erreur lors de la recuperation du nombre d'enregistrement: " + e.getMessage(), 1);
		} finally
		{
			closeRessource(null, stat, rs);
		}
	}

	/**
	 * Compacte la base de données en effectuant un VACUUM
	 * 
	 * @throws DBException
	 */
	public void compacter() throws DBException
	{
		if (connexion == null)
		{
			return;
		}
		notifyInProgressAction("Compactage de la base de données...");
		// hack foireux pour poouvoir recevoir l'event de l'action qui n'est pas recu sinon la méthode est bloquante...
		new Thread()
		{
			@Override
			public void run()
			{
				Statement stat = null;
				try
				{
					stat = connexion.createStatement();
					// Pour l'automatique ça serait : "PRAGMA auto_vacuum = 1"
					stat.execute("VACUUM");
					stat.close();
					notifyCompletedAction("La base de données a été compactée");
				}
				catch (SQLException e)
				{
					notifyFailedAction("Une erreur est survenue pendant le compactage de la base de données");
				} finally
				{
					closeRessource(null, stat, null);
				}
			}
		}.start();
	}

	/**
	 * Permet d'ajouter un enregistrement a la base
	 * 
	 * @param nom
	 *            le nom sous lequel il apparaitra
	 * @param duree
	 *            la duree de cette enregistrement
	 * @param idCat
	 *            la categorie a laquelle il appartient
	 * @param enregistrement
	 *            l'enregistrement sous la forme d'un tableau de byte
	 * @throws DBException
	 */
	public void ajouterEnregistrement(final String nom, final int duree, final int idCat, final byte[] enregistrement,
			final int idSuj) throws DBException
	{
		if (connexion == null)
		{
			return;
		}
		if (!this.categorieExiste(idCat))// On verifie si la categorie existe
		{
			notifyFailedAction("Impossible d'ajouter l'enregistrement");
			throw new DBException("Catégorie inexistante.", 3);
		}
		if (!this.sujetExiste(idSuj))// test l'existance de la categorie
		{
			notifyFailedAction("Impossible d'ajouter l'enregistrement");
			throw new DBException("Sujet inexistant.", 3);
		}
		if (this.enregistrementExist(nom))
		{
			notifyFailedAction("Impossible d'ajouter l'enregistrement");
			throw new DBException("Nom déjà utilisé.", 3);
		}
		notifyInProgressAction("Ajout de l'enregistrement dans la base de données...");
		PreparedStatement ps = null;
		try
		{
			ps = connexion
					.prepareStatement("INSERT INTO enregistrements(enregistrement, duree, taille, nom, idCat, idSuj) VALUES (?, ?, ?, ?, ?, ?);");
			ps.setBytes(1, enregistrement);
			ps.setInt(2, duree);
			ps.setInt(3, enregistrement.length);
			ps.setString(4, nom);
			ps.setInt(5, idCat);
			ps.setInt(6, idSuj);

			if (ps.executeUpdate() > 0)
			{
				notifyUpdateDataBase();
				notifyCompletedAction("L'enregistrement a été ajouté dans la base de données");
			}
		}
		catch (SQLException e)
		{
			notifyFailedAction("Impossible d'ajouter l'enregistrement");
			throw new DBException("Erreur lors de l'ajout de l'enregistrement : " + e.getMessage(), 3);
		} finally
		{
			closeRessource(ps, null, null);
		}
	}

	/**
	 * Permet de supprimer un enregistrement
	 * 
	 * @param id
	 *            id de l'enregistrement a supprimer.
	 * @throws DBException
	 */
	public void supprimerEnregistrement(final int id) throws DBException
	{
		if (connexion == null)
		{
			return;
		}
		notifyInProgressAction("Suppresion de l'enregistrement...");
		PreparedStatement ps = null;
		try
		{

			ps = connexion.prepareStatement("DELETE FROM enregistrements WHERE id=?");
			ps.setInt(1, id);// On rempli les trou
			if (ps.executeUpdate() > 0)// On execute la requete et on test la reussite de cette dernier
			{
				notifyUpdateDataBase();
				notifyCompletedAction("L'enregistrement a été supprimé");
			}
		}
		catch (SQLException e)
		{
			notifyFailedAction("Impossible de supprimer l'enregistrement");
			throw new DBException(e.getLocalizedMessage(), 3);
		} finally
		{
			closeRessource(ps, null, null);
		}
	}

	/**
	 * Permet de modifier un enregistrement dans son ensemble
	 * 
	 * @param id
	 *            le numero identifiant l'enregistrement
	 * @param nom
	 *            le nouveau nom
	 * @param duree
	 *            la nouvelle duree
	 * @param enregistrement
	 *            le nouvelle enregistrement
	 * @param idCat
	 *            la nouvelle categorie
	 * @throws DBException
	 */
	public void modifierEnregistrement(final int id, final String nom, final int duree, final byte[] enregistrement,
			final int idCat, final int idSuj) throws DBException
	{
		if (connexion == null)
		{
			return;
		}
		if (!this.categorieExiste(idCat))// On test si la categorie est existante
		{
			notifyFailedAction("Impossible de mettre à jour l'enregistrement");
			throw new DBException("Categorie inexistante.", 3);
		}
		if (!this.sujetExiste(idSuj))// test l'existance de la categorie
		{
			notifyFailedAction("Impossible de mettre à jour l'enregistrement");
			throw new DBException("sujet inexistante.", 3);
		}
		if (this.enregistrementExist(nom))
		{
			notifyFailedAction("Impossible de mettre à jour l'enregistrement");
			throw new DBException("Nom déjà utilisé.", 3);
		}
		notifyInProgressAction("Mise à jour de l'enregistrement...");

		PreparedStatement ps = null;
		try
		{
			ps = connexion
					.prepareStatement("UPDATE enregistrements SET enregistrement=?, nom=?, duree=?, taille=?, idCat=?, idsuj=? WHERE id=?;");// On
																																				// prepare
			ps.setBytes(1, enregistrement);// On rempli
			ps.setString(2, nom);
			ps.setInt(3, duree);
			ps.setInt(4, enregistrement.length);
			ps.setInt(5, idCat);
			ps.setInt(6, idSuj);
			ps.setInt(7, id);

			if (ps.executeUpdate() > 0)// On execute et on test la reussite
			{
				notifyCompletedAction("L'enregistrement a été mis à jour");
				notifyUpdateDataBase();
			}
		}
		catch (SQLException e)
		{
			notifyFailedAction("Impossible de mettre à jour l'enregistrement");
			throw new DBException("Erreur lors de la modification de l'enregistrement : " + e.getMessage(), 3);
		} finally
		{
			closeRessource(ps, null, null);
		}
	}

	/**
	 * Permet de modifier un enregistrement sans modifier son contenu
	 * 
	 * @param id
	 *            le numero identifiant l'enregistrement
	 * @param nom
	 *            le nouveau nom
	 * @param duree
	 *            la nouvelle duree
	 * @param taille
	 *            du nouvelle enregistrement
	 * @param idCat
	 *            la nouvelle categorie
	 * @throws DBException
	 */
	public void modifierEnregistrement(final int id, final String nom, final int duree, final int taille,
			final int idCat, final int idSuj) throws DBException
	{
		if (connexion == null)
		{
			return;
		}
		if (!this.categorieExiste(idCat))// On test si la categorie existe
		{
			notifyFailedAction("Impossible de mettre à jour l'enregistrement");
			throw new DBException("Catégorie inexistante.", 3);
		}
		if (!this.sujetExiste(idSuj))// test l'existance de la categorie
		{
			notifyFailedAction("Impossible de mettre à jour l'enregistrement");
			throw new DBException("Sujet inexistante.", 3);
		}
		if (this.enregistrementExist(nom))
		{
			notifyFailedAction("Impossible de mettre à jour l'enregistrement");
			throw new DBException("Nom déjà utilisé.", 3);
		}
		notifyInProgressAction("Mise à jour de l'enregistrement...");
		PreparedStatement ps = null;
		try
		{
			ps = connexion
					.prepareStatement("UPDATE enregistrements SET nom=?, duree=?, taille=?, idCat=?, idsuj=? WHERE id=?;");
			ps.setString(1, nom);// Remplissage de la requete
			ps.setInt(2, duree);
			ps.setInt(3, taille);
			ps.setInt(4, idCat);
			ps.setInt(5, idSuj);
			ps.setInt(6, id);

			if (ps.executeUpdate() > 0)// Execution et test de reussite dans la foulee
			{
				notifyCompletedAction("L'enregistrement a été mis à jour");
				notifyUpdateDataBase();
			}
		}
		catch (SQLException e)
		{
			notifyFailedAction("Impossible de mettre à jour l'enregistrement");
			throw new DBException("Erreur lors de la modification de l'enregistrement : " + e.getMessage(), 3);
		} finally
		{
			closeRessource(ps, null, null);
		}
	}

	/**
	 * Permet de modifier le nom de l'enregistrement
	 * 
	 * @param id
	 *            id de l'enregistrement a modifier
	 * @param nom
	 *            le nouveau nom
	 * @throws DBException
	 */
	public void modifierEnregistrementNom(final int id, final String nom) throws DBException
	{
		if (connexion == null)
		{
			return;
		}
		if (this.enregistrementExist(nom))
		{
			notifyFailedAction("Impossible de renommer l'enregistrement");
			throw new DBException("Nom déjà utilisé.", 3);
		}
		notifyInProgressAction("Renommage de l'enregistrement...");
		PreparedStatement ps = null;
		try
		{
			ps = connexion.prepareStatement("UPDATE enregistrements SET nom=? WHERE id=?;");
			ps.setString(1, nom);// Remplissage de la requete
			ps.setInt(2, id);

			if (ps.executeUpdate() > 0)// execution et test de la reussite de la requete
			{
				notifyCompletedAction("L'enregistrement a été renommé");
				notifyUpdateDataBase();
			}
		}
		catch (SQLException e)
		{
			notifyFailedAction("Impossible de renommer l'enregistrement");
			throw new DBException("Erreur lors de la modification du nom : " + e.getMessage(), 3);
		} finally
		{
			closeRessource(ps, null, null);
		}
	}

	/**
	 * Permet de modier le champ duree d'un enregistrement dans la base. (Pas la duree du veritable enregistrement)
	 * 
	 * @param id
	 *            l'id de l'enregistrement a modifier
	 * @param duree
	 *            la nouvelle duree a ajouter dans la base
	 * @throws DBException
	 */
	public void modifierEnregistrementDuree(final int id, final int duree) throws DBException
	{
		if (connexion == null)
		{
			return;
		}
		notifyInProgressAction("Changement de la durée de l'enregistrement...");
		PreparedStatement ps = null;
		try
		{
			ps = connexion.prepareStatement("UPDATE enregistrements SET duree=? WHERE id=?;");
			ps.setInt(1, duree);// Remplissage de la requete
			ps.setInt(2, id);

			if (ps.executeUpdate() > 0)// execution et test de la reussite de la requete
			{
				notifyCompletedAction("La durée de l'enregistrement a été mise à jour");
				notifyUpdateDataBase();
			}
		}
		catch (SQLException e)
		{
			notifyFailedAction("Impossible de changer la durée de l'enregistrement");
			throw new DBException("Erreur lors de la modification de la durée : " + e.getMessage(), 3);
		} finally
		{
			closeRessource(ps, null, null);
		}
	}

	/**
	 * Permet de de modifier le champ taille d'un enregistrement dans la base. (Pas la taille du veritable
	 * enregistrement)
	 * 
	 * @param id
	 *            l'id de l'enregistrement a modifier
	 * @param taille
	 *            la nouvelle taille a ajouter dans la base
	 * @throws DBException
	 */
	public void modifierEnregistrementTaille(final int id, final int taille) throws DBException
	{
		if (connexion == null)
		{
			return;
		}
		notifyInProgressAction("Changement de la taille de l'enregistrement...");
		PreparedStatement ps = null;
		try
		{
			ps = connexion.prepareStatement("UPDATE enregistrements SET taille=? WHERE id=?;");
			ps.setInt(1, taille);// Remplissage de la requete
			ps.setInt(2, id);

			if (ps.executeUpdate() > 0)// execution et test de la reussite de la requete
			{
				notifyCompletedAction("La taille de l'enregistrement a été mise à jour");
				notifyUpdateDataBase();
			}

		}
		catch (SQLException e)
		{
			notifyFailedAction("Impossible de changer la taille de l'enregistrement");
			throw new DBException("Erreur lors de la modification de la taille : " + e.getMessage(), 3);
		} finally
		{
			closeRessource(ps, null, null);
		}
	}

	/**
	 * Permet de modifier la categorie d'un enregistrement
	 * 
	 * @param id
	 *            id de l'enregistrement a modifier
	 * @param idCat
	 *            nouvelle id de la categorie correspondant a la nouvelle categorie
	 * @throws DBException
	 */
	public void modifierEnregistrementCategorie(final int id, final int idCat) throws DBException
	{
		if (connexion == null)
		{
			return;
		}
		if (!this.categorieExiste(idCat))// test l'existance de la categorie
		{
			notifyFailedAction("Impossible de changer la catégorie de l'enregistrement");
			throw new DBException("Catégorie inexistante.", 3);
		}
		notifyInProgressAction("Changement de la catégorie de l'enregistrement...");
		PreparedStatement ps = null;
		try
		{
			ps = connexion.prepareStatement("UPDATE enregistrements SET idCat=? WHERE id=?;");
			ps.setInt(1, idCat);// Remplissage de la requete
			ps.setInt(2, id);

			if (ps.executeUpdate() > 0)// execution et test de la reussite de la requete
			{
				notifyCompletedAction("La catégorie de l'enregistrement a été mise à jour");
				notifyUpdateDataBase();
			}
		}
		catch (SQLException e)
		{
			notifyFailedAction("Impossible de changer la catégorie de l'enregistrement");
			throw new DBException("Erreur lors de la modification de la catégorie : " + e.getMessage(), 3);
		} finally
		{
			closeRessource(ps, null, null);
		}
	}

	/**
	 * Permet de modifier la categorie d'un enregistrement a partir du nom de la categorie
	 * 
	 * @param id
	 *            id de l'enregistrement a modifier
	 * @param nomCat
	 *            le nom de la categorie
	 * @throws DBException
	 */
	public void modifierEnregistrementCategorie(final int id, final String nomCat) throws DBException
	{
		if (connexion == null)
		{
			return;
		}
		if (!this.categorieExiste(nomCat))// test l'existance de la categorie
		{
			notifyFailedAction("Impossible de changer la catégorie de l'enregistrement");
			throw new DBException("Catégorie inexistante.", 3);
		}
		notifyInProgressAction("Changement de la catégorie de l'enregistrement...");
		PreparedStatement ps = null;
		try
		{
			ps = connexion
					.prepareStatement("UPDATE enregistrements SET idCat=(SELECT idcat FROM categorie WHERE nomCat=?) WHERE id=?;");// preparation
																																	// de
																																	// la
																																	// requete
			ps.setString(1, nomCat);// Remplissage de la requete
			ps.setInt(2, id);

			if (ps.executeUpdate() > 0)// execution et test de la reussite de la requete
			{
				notifyCompletedAction("La catégorie de l'enregistrement a été mise à jour");
				notifyUpdateDataBase();
			}

		}
		catch (SQLException e)
		{
			notifyFailedAction("Impossible de changer la catégorie de l'enregistrement");
			throw new DBException("Erreur lors de la modification de la catégorie : " + e.getMessage(), 3);
		} finally
		{
			closeRessource(ps, null, null);
		}
	}

	/**
	 * Permet de modifier la categorie d'un enregistrement
	 * 
	 * @param id
	 *            id de l'enregistrement a modifier
	 * @param idCat
	 *            nouvelle id de la categorie correspondant a la nouvelle categorie
	 * @throws DBException
	 */
	public void modifierEnregistrementSujet(final int id, final int idSuj) throws DBException
	{
		if (connexion == null)
		{
			return;
		}
		if (!this.sujetExiste(idSuj))// test l'existance de la categorie
		{
			notifyFailedAction("Impossible de changer le sujet de l'enregistrement");
			throw new DBException("Sujet inexistant.", 3);
		}
		notifyInProgressAction("Changement du sujet de l'enregistrement...");
		PreparedStatement ps = null;
		try
		{
			ps = connexion.prepareStatement("UPDATE enregistrements SET idSuj=? WHERE id=?;");
			ps.setInt(1, idSuj);// Remplissage de la requete
			ps.setInt(2, id);

			if (ps.executeUpdate() > 0)// execution et test de la reussite de la requete
			{
				notifyCompletedAction("Le sujet de l'enregistrement a été mis à jour");
				notifyUpdateDataBase();
			}
		}
		catch (SQLException e)
		{
			notifyFailedAction("Impossible de changer le sujet de l'enregistrement");
			throw new DBException("Erreur lors de la modification du sujet : " + e.getMessage(), 3);
		} finally
		{
			closeRessource(ps, null, null);
		}
	}

	/**
	 * Permet de modifier la categorie d'un enregistrement a partir du nom de la categorie
	 * 
	 * @param id
	 *            id de l'enregistrement a modifier
	 * @param nomCat
	 *            le nom de la categorie
	 * @throws DBException
	 */
	public void modifierEnregistrementSujet(final int id, final String nomSuj) throws DBException
	{
		if (connexion == null)
		{
			return;
		}
		if (!this.sujetExiste(nomSuj))// test l'existance de la categorie
		{
			notifyFailedAction("Impossible de changer le sujet de l'enregistrement");
			throw new DBException("Sujet inexistant.", 3);
		}
		notifyInProgressAction("Changement du sujet de l'enregistrement...");
		PreparedStatement ps = null;
		try
		{
			ps = connexion
					.prepareStatement("UPDATE enregistrements SET idSuj=(SELECT idsuj FROM sujet WHERE nomSuj=?) WHERE id=?;");// preparation
																																// de
																																// la
																																// requete
			ps.setString(1, nomSuj);// Remplissage de la requete
			ps.setInt(2, id);

			if (ps.executeUpdate() > 0)// execution et test de la reussite de la requete
			{
				notifyCompletedAction("Le sujet de l'enregistrement a été mis à jour");
				notifyUpdateDataBase();
			}

		}
		catch (SQLException e)
		{
			notifyFailedAction("Impossible de changer le sujet de l'enregistrement");
			throw new DBException("Erreur lors de la modification du sujet : " + e.getMessage(), 3);
		} finally
		{
			closeRessource(ps, null, null);
		}
	}

	/**
	 * Permet de recuperer l'enregistrement lui-meme et non ses informations
	 * 
	 * @param id
	 *            id de l'enregistrement a recuperer
	 * @return retourne l'enregistrement sous forme de tableau de byte
	 * @throws DBException
	 */
	public byte[] recupererEnregistrement(final int id) throws DBException
	{
		byte[] retour = null;
		if (connexion == null)
		{
			return null;
		}
		notifyInProgressAction("Récupération de l'enregistrement depuis la base de données...");
		PreparedStatement ps = null;
		ResultSet rs = null;
		try
		{
			ps = connexion.prepareStatement("SELECT enregistrement FROM enregistrements WHERE id=?");
			ps.setInt(1, id);// Remplissage de la requete
			rs = ps.executeQuery();// execute la requete
			if (rs.next())// s'il y a un retour on renvoie le tableau de byte sinon une exception est levee
			{
				retour = rs.getBytes("enregistrement");
				notifyCompletedAction("L'enregistrement a été récupéré");
				return retour;
			}
			throw new DBException("Enregistrement inexistant.", 3);
		}
		catch (Exception e)
		{
			notifyFailedAction("Impossible de récupérer l'enregistrement");
			throw new DBException("Erreur lors de la récuperation de l'enregistrement : " + e.getMessage(), 3);
		} finally
		{
			closeRessource(ps, null, rs);
		}
	}

	/**
	 * Permet d'ajouter une categorie
	 * 
	 * @param nom
	 *            le nom de la nouvelle categorie
	 * @throws DBException
	 */
	public void ajouterCategorie(final String nom) throws DBException
	{
		if (connexion == null)
		{
			return;
		}
		if (this.categorieExiste(nom))
		{
			notifyFailedAction("Impossible d'ajouter la catégorie");
			throw new DBException("Nom de catégorie déjà utilisé.", 3);
		}
		notifyInProgressAction("Ajout de la catégorie...");
		PreparedStatement ps = null;
		try
		{
			ps = connexion.prepareStatement("INSERT INTO categorie (nomcat) VALUES (?)");// preparation
																							// de la
																							// requete
			ps.setString(1, nom);// Remplissage de la requete
			if (ps.executeUpdate() > 0)// execution et test de la reussite de la requete
			{
				notifyCompletedAction("La catégorie a été ajoutée");
				notifyUpdateDataBase();
			}
		}
		catch (SQLException e)
		{
			notifyFailedAction("Impossible d'ajouter la catégorie");
			throw new DBException("Erreur lors de l'ajout de la catégorie : " + e.getMessage(), 3);
		} finally
		{
			closeRessource(ps, null, null);
		}
	}

	/**
	 * Permet de recuperer la liste des categories avec les colonnes dans cette ordre: nomCat, idCat
	 * 
	 * @return Le resultat sous la forme d'un tableau parcourable dans un sens
	 * @throws DBException
	 */
	public List<LigneEnregistrement> getListeCategorie() throws DBException
	{
		if (connexion == null)
		{
			return null;
		}
		Statement stat = null;
		ResultSet rs = null;
		List<LigneEnregistrement> retour = null;
		List<String> colonne = new LinkedList<String>();
		colonne.add("nomcat");
		colonne.add("idcat");
		try
		{
			stat = connexion.createStatement();// creation du Statement
			rs = stat.executeQuery("SELECT nomcat, idcat FROM categorie;");// execution de la requete
			retour = ResultatSelect.convertirResultatSet(rs, colonne);
		}
		catch (SQLException e)
		{
			throw new DBException("Erreur lors de la récuperation des catégories : " + e.getMessage(), 3);
		} finally
		{
			closeRessource(null, stat, rs);
		}
		return retour;
	}

	/**
	 * Supprime une categorie existante (ou non)
	 * 
	 * @param id
	 *            l'id de la categorie a supprimer
	 * @throws DBException
	 */
	public void supprimerCategorie(final int id) throws DBException// comment on fait pour les enregistrements de cette
																	// cate ?
	{
		if (connexion == null)
		{
			return;
		}
		notifyInProgressAction("Suppresion de la catégorie...");
		PreparedStatement ps = null;
		try
		{
			ps = connexion.prepareStatement("DELETE FROM categorie WHERE idCat=?");// preparation de
																					// la requete
			ps.setInt(1, id);// Remplissage de la requete
			if (ps.executeUpdate() > 0)// execution et test de la reussite de la requete
			{
				notifyCompletedAction("La catégorie a été supprimée");
				notifyUpdateDataBase();
			}
		}
		catch (SQLException e)
		{
			notifyFailedAction("Impossible de supprimer la catégorie");
			throw new DBException("Erreur lors de la suppression de la catégorie : " + e.getMessage(), 3);
		} finally
		{
			closeRessource(ps, null, null);
		}
	}

	/**
	 * Permet de changer le nom d'une categorie
	 * 
	 * @param id
	 *            l'id de la categorie a modifier
	 * @param nom
	 *            le nouveau nom
	 * @throws DBException
	 */
	public void modifierCategorie(final int id, final String nom) throws DBException
	{
		if (connexion == null)
		{
			return;
		}
		if (this.categorieExiste(nom))
		{
			notifyFailedAction("Impossible de renommer la catégorie");
			throw new DBException("Nom de catégorie déjà utilisé.", 3);
		}
		notifyInProgressAction("Renommage de la catégorie...");
		PreparedStatement ps = null;
		try
		{
			ps = connexion.prepareStatement("UPDATE categorie SET nomCat=? WHERE idCat=?");// preparation
																							// de la
																							// requete
			ps.setString(1, nom);// Remplissage de la requete
			ps.setInt(2, id);
			if (ps.executeUpdate() > 0)// execution et test de la reussite de la requete
			{
				notifyCompletedAction("La catégorie a été renommée");
				notifyUpdateDataBase();
			}
		}
		catch (SQLException e)
		{
			notifyFailedAction("Impossible de renommer la catégorie");
			throw new DBException("Erreur lors de la modification de la categorie: " + e.getMessage(), 3);
		} finally
		{
			closeRessource(ps, null, null);
		}
	}

	/**
	 * Recupere le nom de la categorie correspondant a cette id
	 * 
	 * @param idCat
	 *            id de la categorie
	 * @return le nom de la categorie
	 * @throws DBException
	 */
	public String getCategorie(final int idCat) throws DBException
	{
		String retour;
		if (connexion == null)
		{
			return null;
		}
		if (!this.categorieExiste(idCat))// test l'existance de la categorie
		{
			throw new DBException("Catégorie inexistante.", 3);
		}
		PreparedStatement ps = null;
		ResultSet rs = null;
		try
		{

			ps = connexion.prepareStatement("SELECT nomcat FROM categorie WHERE idcat=?;");// preparation
																							// de la
																							// requete
			ps.setInt(1, idCat);// Remplissage de la requete
			rs = ps.executeQuery();// execution de la requete
			retour = rs.getString(1);
			rs.close();
			ps.close();
		}
		catch (SQLException e)
		{
			throw new DBException("Erreur lors de la recuperation de la catégorie : " + e.getMessage(), 3);
		} finally
		{
			closeRessource(ps, null, rs);
		}
		return retour;
	}

	/**
	 * Recupere l'id d'une categorie a partir du nom. S'il y a plusieur categorie du meme nom, il renvera le premier id
	 * 
	 * @param nomCat
	 *            le nom de la categorie
	 * @return l'id de la categorie
	 * @throws DBException
	 */
	public int getCategorie(final String nomCat) throws DBException
	{
		int retour;
		if (connexion == null)
		{
			return -1;
		}
		PreparedStatement ps = null;
		ResultSet rs = null;
		try
		{
			ps = connexion.prepareStatement("SELECT idcat FROM categorie WHERE nomcat=?;");// preparation
																							// de la
																							// requete
			ps.setString(1, nomCat);// Remplissage de la requete
			rs = ps.executeQuery();
			retour = rs.getInt(1);
		}
		catch (SQLException e)
		{
			throw new DBException("Erreur lors de la recuperation de la catégorie : " + e.getMessage(), 3);
		} finally
		{
			closeRessource(ps, null, rs);
		}
		return retour;
	}

	/**
	 * Permet d'ajouter une categorie
	 * 
	 * @param nom
	 *            le nom de la nouvelle categorie
	 * @throws DBException
	 */
	public void ajouterSujet(final String nom) throws DBException
	{
		if (connexion == null)
		{
			return;
		}
		if (this.sujetExiste(nom))
		{
			notifyFailedAction("Impossible d'ajouter le sujet");
			throw new DBException("Nom de sujet déjà utilisé.", 3);
		}
		notifyInProgressAction("Ajout du sujet...");
		PreparedStatement ps = null;
		try
		{
			ps = connexion.prepareStatement("INSERT INTO sujet (nomsuj) VALUES (?)");// preparation de
																						// la requete
			ps.setString(1, nom);// Remplissage de la requete
			if (ps.executeUpdate() > 0)// execution et test de la reussite de la requete
			{
				notifyCompletedAction("Le sujet a été ajouté");
				notifyUpdateDataBase();
			}
		}
		catch (SQLException e)
		{
			notifyFailedAction("Impossible d'ajouter le sujet");
			throw new DBException("Erreur lors de l'ajout du sujet : " + e.getMessage(), 3);
		} finally
		{
			closeRessource(ps, null, null);
		}
	}

	/**
	 * Supprime une categorie existante (ou non)
	 * 
	 * @param id
	 *            l'id de la categorie a supprimer
	 * @throws DBException
	 */
	public void supprimerSujet(final int id) throws DBException// comment on fait pour les enregistrements de cette cate
																// ?
	{
		if (connexion == null)
		{
			return;
		}
		notifyInProgressAction("Suppression du sujet...");
		PreparedStatement ps = null;
		try
		{
			ps = connexion.prepareStatement("DELETE FROM sujet WHERE idSuj=?");// preparation de la
																				// requete
			ps.setInt(1, id);// Remplissage de la requete
			if (ps.executeUpdate() > 0)// execution et test de la reussite de la requete
			{
				notifyCompletedAction("Le sujet a été supprimé");
				notifyUpdateDataBase();
			}
		}
		catch (SQLException e)
		{
			notifyFailedAction("Impossible de supprimer le sujet");
			throw new DBException("Erreur lors de la suppression du sujet : " + e.getMessage(), 3);
		} finally
		{
			closeRessource(ps, null, null);
		}

	}

	/**
	 * Permet de recuperer la liste des categories avec les colonnes dans cette ordre: nomCat, idCat
	 * 
	 * @return Le resultat sous la forme d'un tableau parcourable dans un sens
	 * @throws DBException
	 */
	public List<LigneEnregistrement> getListeSujet() throws DBException
	{
		if (connexion == null)
		{
			return null;
		}
		Statement stat = null;
		ResultSet rs = null;
		List<LigneEnregistrement> retour = null;
		List<String> colonne = new LinkedList<String>();
		colonne.add("nomsuj");
		colonne.add("idsuj");
		try
		{
			stat = connexion.createStatement();// creation du Statement
			rs = stat.executeQuery("SELECT nomsuj, idsuj FROM sujet;");// execution de la requete
			retour = ResultatSelect.convertirResultatSet(rs, colonne);
		}
		catch (SQLException e)
		{
			throw new DBException("Erreur lors de la récuperation des sujets : " + e.getMessage(), 3);
		} finally
		{
			closeRessource(null, stat, rs);
		}
		return retour;
	}

	/**
	 * Permet de changer le nom d'une categorie
	 * 
	 * @param id
	 *            l'id de la categorie a modifier
	 * @param nom
	 *            le nouveau nom
	 * @throws DBException
	 */
	public void modifierSujet(final int id, final String nom) throws DBException
	{
		if (connexion == null)
		{
			return;
		}
		if (this.sujetExiste(nom))
		{
			notifyFailedAction("Impossible de renommer le sujet");
			throw new DBException("Nom de sujet déjà utilisé.", 3);
		}
		notifyInProgressAction("Renommage du sujet...");
		PreparedStatement ps = null;
		try
		{
			ps = connexion.prepareStatement("UPDATE sujet SET nomSuj=? WHERE idSuj=?");// preparation
																						// de la
																						// requete
			ps.setString(1, nom);// Remplissage de la requete
			ps.setInt(2, id);
			if (ps.executeUpdate() > 0)// execution et test de la reussite de la requete
			{
				notifyCompletedAction("Le sujet a été renommé");
				notifyUpdateDataBase();
			}
		}
		catch (SQLException e)
		{
			notifyFailedAction("Impossible de renommer le sujet");
			throw new DBException("Erreur lors de la modification du sujet : " + e.getMessage(), 3);
		} finally
		{
			closeRessource(ps, null, null);
		}
	}

	/**
	 * Recupere le nom de la categorie correspondant a cette id
	 * 
	 * @param idSuj
	 *            id de la categorie
	 * @return le nom de la categorie
	 * @throws DBException
	 */
	public String getSujet(final int idSuj) throws DBException
	{
		String retour;
		PreparedStatement ps = null;
		ResultSet rs = null;
		if (connexion == null)
		{
			return null;
		}
		if (!this.categorieExiste(idSuj))// test l'existance de la categorie
		{
			throw new DBException("Sujet inexistant.", 3);
		}
		try
		{

			ps = connexion.prepareStatement("SELECT nomSuj FROM sujet WHERE idSuj=?;");// preparation
																						// de la
																						// requete
			ps.setInt(1, idSuj);// Remplissage de la requete
			rs = ps.executeQuery();// execution de la requete
			retour = rs.getString(1);
		}
		catch (SQLException e)
		{
			throw new DBException("Erreur lors de la récupération du sujet : " + e.getMessage(), 3);
		} finally
		{
			closeRessource(ps, null, rs);
		}
		return retour;
	}

	/**
	 * Recupere l'id d'une categorie a partir du nom. S'il y a plusieur categorie du meme nom, il renvera le premier id
	 * 
	 * @param nomSuj
	 *            le nom de la categorie
	 * @return l'id de la categorie
	 * @throws DBException
	 */
	public int getSujet(final String nomSuj) throws DBException
	{
		int retour;
		PreparedStatement ps = null;
		ResultSet rs = null;
		if (connexion == null)
		{
			return -1;
		}
		try
		{
			ps = connexion.prepareStatement("SELECT idSuj FROM sujet WHERE nomSuj=?;");// preparation
																						// de la
																						// requete
			ps.setString(1, nomSuj);// Remplissage de la requete
			rs = ps.executeQuery();
			retour = rs.getInt(1);
		}
		catch (SQLException e)
		{
			throw new DBException("Erreur lors de la récupération du sujet : " + e.getMessage(), 3);
		} finally
		{
			closeRessource(ps, null, rs);
		}
		return retour;
	}

	/**
	 * Cette fonction creer la structure de la base de donne.
	 * 
	 * @throws DBException
	 */
	public void createDatabase() throws DBException
	{
		Statement stat = null;
		try
		{
			stat = connexion.createStatement();// creation du Statement
			stat.executeUpdate("DROP TABLE if exists enregistrements;");// suppression des table si elle existe
			stat.executeUpdate("DROP TABLE if exists categorie;");
			stat.executeUpdate("DROP TABLE if exists sujet;");
			// Creation des table et verification de la bonne execution des requetes
			if (stat.executeUpdate("CREATE TABLE categorie (idcat  INTEGER PRIMARY KEY AUTOINCREMENT, nomcat VARCHAR2(128) UNIQUE);") != 0)
			{
				throw new DBException("Erreur de création de la table categorie.", 5);
			}
			// FIXME ajouter la reference pour le champ idcat
			if (stat.executeUpdate("CREATE TABLE enregistrements (id  INTEGER PRIMARY KEY AUTOINCREMENT, enregistrement BLOB, duree INTEGER, taille INTEGER, nom VARCHAR2(128) UNIQUE, idcat INTEGER, idsuj INTEGER);") != 0)
			{
				throw new DBException("Erreur de création de la table enregistrement.", 5);
			}
			// FIXME ajouter la reference pour le champ idcat
			if (stat.executeUpdate("CREATE TABLE sujet (idsuj  INTEGER PRIMARY KEY AUTOINCREMENT, nomsuj VARCHAR2(128) UNIQUE);") != 0)
			{
				throw new DBException("Erreur de création de la table enregistrement.", 5);
			}
		}
		catch (SQLException e)
		{
			throw new DBException("Erreur lors de la création de la base : " + e.getMessage(), 3);
		} finally
		{
			closeRessource(null, stat, null);
		}
	}

	/**
	 * Verifie si une categorie existe
	 * 
	 * @param idCat
	 *            id de la categorie a verifier
	 * @return true si la categorie existe et false sinon
	 * @throws DBException
	 */
	private boolean categorieExiste(final int idCat) throws DBException
	{
		if (connexion == null)
		{
			return false;
		}
		PreparedStatement ps = null;
		boolean retour = false;
		ResultSet rs = null;
		try
		{
			ps = connexion.prepareStatement("SELECT 1 FROM categorie WHERE idcat=?");
			ps.setInt(1, idCat);
			rs = ps.executeQuery();

			if (rs.next())
			{
				retour = true;
			}
		}
		catch (SQLException e)
		{
			throw new DBException(
					"Problème lors de la vérification de l'existence de la catégorie : " + e.getMessage(), 1);
		} finally
		{
			closeRessource(ps, null, rs);
		}
		return retour;
	}

	/**
	 * Verifie si une categorie existe
	 * 
	 * @param nomCat
	 *            nom de la categorie a verifier
	 * @return true si la categorie existe et false sinon
	 * @throws DBException
	 */
	private boolean categorieExiste(final String nomCat) throws DBException
	{
		if (connexion == null)
		{
			return false;
		}
		PreparedStatement ps = null;
		boolean retour = false;
		ResultSet rs = null;
		try
		{
			ps = connexion.prepareStatement("SELECT 1 FROM categorie WHERE nomcat=?");
			ps.setString(1, nomCat);
			rs = ps.executeQuery();

			if (rs.next())
			{
				retour = true;
			}
		}
		catch (SQLException e)
		{
			throw new DBException(
					"Problème lors de la vérification de l'existance de la catégorie : " + e.getMessage(), 1);
		} finally
		{
			closeRessource(ps, null, rs);
		}
		return retour;
	}

	/**
	 * Verifie si une categorie existe
	 * 
	 * @param idSuj
	 *            id de la categorie a verifier
	 * @return true si la categorie existe et false sinon
	 * @throws DBException
	 */
	private boolean sujetExiste(final int idSuj) throws DBException
	{
		if (connexion == null)
		{
			return false;
		}
		PreparedStatement ps = null;
		boolean retour = false;
		ResultSet rs = null;
		try
		{
			ps = connexion.prepareStatement("SELECT 1 FROM sujet WHERE idSuj=?");
			ps.setInt(1, idSuj);
			rs = ps.executeQuery();

			if (rs.next())
			{
				retour = true;
			}
		}
		catch (SQLException e)
		{
			throw new DBException("Problème lors de la vérification de l'existence du sujet : " + e.getMessage(), 1);
		} finally
		{
			closeRessource(ps, null, rs);
		}
		return retour;
	}

	/**
	 * Verifie si une categorie existe
	 * 
	 * @param nomSuj
	 *            nom de la categorie a verifier
	 * @return true si la categorie existe et false sinon
	 * @throws DBException
	 */
	private boolean sujetExiste(final String nomSuj) throws DBException
	{
		if (connexion == null)
		{
			return false;
		}
		PreparedStatement ps = null;
		boolean retour = false;
		ResultSet rs = null;
		try
		{
			ps = connexion.prepareStatement("SELECT 1 FROM sujet WHERE nomSuj=?");
			ps.setString(1, nomSuj);
			rs = ps.executeQuery();

			if (rs.next())
			{
				retour = true;
			}
		}
		catch (SQLException e)
		{
			throw new DBException("Problème lors de la vérification de l'existence du sujet : " + e.getMessage(), 1);
		} finally
		{
			closeRessource(ps, null, rs);
		}
		return retour;
	}

	/**
	 * Verifie l'existance d'un sujet par son nom
	 * 
	 * @param nom
	 *            le nom a verifier
	 * @return true si le nom existe
	 * @throws DBException
	 */
	public boolean enregistrementExist(final String nom) throws DBException
	{
		if (connexion == null)
		{
			return false;
		}

		PreparedStatement ps = null;
		boolean retour = false;
		ResultSet rs = null;
		try
		{
			ps = connexion.prepareStatement("SELECT 1 FROM enregistrements WHERE nom=?");
			ps.setString(1, nom);
			rs = ps.executeQuery();

			if (rs.next())
			{
				retour = true;
			}
		}
		catch (SQLException e)
		{
			throw new DBException("Problème lors de la vérification de l'existence du sujet : " + e.getMessage(), 1);
		} finally
		{
			closeRessource(ps, null, rs);
		}
		return retour;
	}

	/**
	 * copie le fichier source dans le fichier resultat retourne vrai si cela réussit
	 */
	private static boolean copyFile(File source, File dest)
	{

		// Declaration et ouverture des flux
		FileInputStream sourceFile = null;
		FileOutputStream destinationFile = null;

		try
		{
			sourceFile = new FileInputStream(source);
			destinationFile = new FileOutputStream(dest);
			// Lecture par segment de 0.5Mo
			byte buffer[] = new byte[512 * 1024];
			int nbLecture;

			while ((nbLecture = sourceFile.read(buffer)) != -1)
			{
				destinationFile.write(buffer, 0, nbLecture);
			}
			sourceFile.close();
			destinationFile.close();
		}
		catch (IOException e)
		{
			logger.log(Level.WARNING, e.getLocalizedMessage());
		} finally
		{
			try
			{
				if (destinationFile != null)
				{
					destinationFile.close();
				}
				if (sourceFile != null)
				{
					sourceFile.close();
				}
			}
			catch (IOException e)
			{
				logger.log(Level.WARNING, e.getLocalizedMessage());
			}
		}

		return true; // Résultat OK
	}

	/**
	 * Ferme les differente ressources
	 * 
	 * @param ps
	 * @param st
	 * @param rs
	 */
	private static void closeRessource(PreparedStatement ps, Statement st, ResultSet rs)
	{
		if (ps != null)
		{
			try
			{
				ps.close();
			}
			catch (SQLException e)
			{
				logger.log(Level.WARNING, e.getMessage());
			}
		}
		if (st != null)
		{
			try
			{
				st.close();
			}
			catch (SQLException e)
			{
				logger.log(Level.WARNING, e.getMessage());
			}
		}
		if (rs != null)
		{
			try
			{
				rs.close();
			}
			catch (SQLException e)
			{
				logger.log(Level.WARNING, e.getMessage());
			}
		}
	}

	// GETTER et SETTER
	/**
	 * getter de la connexion
	 * 
	 * @return la connexion
	 */
	public Connection getConnexion()
	{
		return connexion;
	}

	/**
	 * Renvoie le chemin du fichier de base de donnée
	 * 
	 * @return le chemin du fichier
	 */
	public String getFileName()
	{
		return fileName;
	}
}
