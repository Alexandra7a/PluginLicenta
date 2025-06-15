package org.example.demo1.aiclassification

import org.junit.Test
import org.junit.Assert.*
import org.junit.Before

class AiClassificationTest {

    private lateinit var aiClassification: AiClassification

    @Before
    fun setUp(){
        aiClassification = AiClassification()
    }

    @Test
    fun performAIClassificationValid() {

        val codeResultes = aiClassification.performAIClassification(SQL_SECURE)
        assertEquals("Not Vulnerable",codeResultes.label)

    }

    @Test
    fun performAIClassificationInvalid() {

        val codeResultes = aiClassification.performAIClassification(SQL_INJECTION_VULNERABLE)
        assertEquals("Vulnerable",codeResultes.label)

    }

    companion object {
        const val SQL_INJECTION_VULNERABLE = """
        String sql = "SELECT * FROM users WHERE username='" + username + "' AND password='" + password + "'";
        Statement stmt = conn.createStatement();
        ResultSet rs = stmt.executeQuery(sql);
    """

        const val SQL_SECURE = """
        String sql = "SELECT * FROM users WHERE username=? AND password=?";
        PreparedStatement pstmt = conn.prepareStatement(sql);
        pstmt.setString(1, username);
        pstmt.setString(2, password);
        ResultSet rs = pstmt.executeQuery();
    """
    }
}
